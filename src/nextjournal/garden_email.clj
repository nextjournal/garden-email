(ns nextjournal.garden-email
  (:require [clojure.edn :as edn]
            [babashka.http-client :as http]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [ring.util.request :refer [character-encoding]]
            [ring.util.codec :as codec]
            [clojure.string :as str]
            [malli.core :as m]
            [nextjournal.garden-email.validate :as validate]
            [nextjournal.garden-email.mock :as mock])
  (:import [java.io InputStream]))

(def auth-token (System/getenv "GARDEN_TOKEN"))
(def dev-mode? (nil? auth-token))

(def email-endpoint (or (System/getenv "GARDEN_EMAIL_API_ENDPOINT")
                        "https://email.application.garden"))

(def my-email-address (or (System/getenv "GARDEN_EMAIL_ADDRESS")
                          "my-email-address@example.com"))

(defn plus-address
  "Create a plus-address from an email-address and an extra identifier
  `(plus-address\"foo@example.com\" \"bar\") ;=> \"foo+bar@example.com\"`"
  ([plus]
   (plus-address my-email-address plus))
  ([email-address plus]
   (str/replace-first email-address "@" (str "+" plus "@"))))

(defn- json-request? [request]
  (when-let [type (get-in request [:headers "content-type"])]
    (some? (re-find #"^application/(.+\+)?json" type))))

(defn- read-json [request]
  (when (json-request? request)
    (when-let [^InputStream body (:body request)]
      (let [^String encoding (or (character-encoding request)
                                 "UTF-8")
            body-reader (java.io.InputStreamReader. body encoding)]
        (try
          [true (json/parse-stream body-reader keyword)]
          (catch com.fasterxml.jackson.core.JsonParseException ex
            [false nil]))))))

(defn- strip-prefix [prefix s]
  (if (str/starts-with? s prefix)
    (subs s (count prefix))
    s))

(defn- parse-auth-token [{:as req :keys [headers]}]
  (strip-prefix "Bearer " (headers "authorization")))

(defn- handle-receive [on-receive req]
  ;; we use the same auth token for server -> client and client -> server communication
  (if (= auth-token
         (parse-auth-token req))
    (let [[valid? body] (read-json req)]
      (if valid?
        (do (on-receive body)
            {:status 200})
        {:status  400
         :headers {"Content-Type" "text/plain"}
         :body    "Malformed JSON in request body."}))
    {:status 403}))

(def inbox-path (str (fs/path (System/getenv "GARDEN_STORAGE") ".mailbox")))

;;TODO
;; - [ ] better serialization? (maildir?)
;; - [ ] support attachments
(defn save-to-inbox! [{:as email :keys [message-id]}]
  (fs/create-dirs inbox-path)
  (spit (str (fs/path inbox-path message-id)) (pr-str email)))

(defn delete-from-inbox!
  "Delete an email in the inbox. Takes email as a map, containing `:message-id`"
  [{:as email :keys [message-id]}]
  (fs/delete-if-exists (fs/path inbox-path message-id)))

(defn clear-inbox!
  "Deletes all emails in the inbox"
  []
  (doseq [mail (fs/list-dir inbox-path)]
    (fs/delete mail)))

(defn inbox
  "Get messages from the inbox:
  * `(inbox)` returns all messages in the inbox.
  * `(inbox <message-id>)` returns the specific message identified by the message-id or nil if no matching message was found."
  ([] (into {} (map (fn [path] [(fs/file-name path) (edn/read-string (slurp (str path)))]) (try (fs/list-dir inbox-path)
                                                                                        (catch java.nio.file.NoSuchFileException e nil)))))
  ([message-id] (try (edn/read-string (slurp (str (fs/path inbox-path message-id))))
                     (catch Exception _ nil))))

(defn- send-real-email! [{:as opts :keys [from to subject text html attachments]}]
  (http/post (str email-endpoint "/send")
             {:headers {"content-type" "application/json"
                        "authorization" (str "Bearer " auth-token)}
              :body (json/encode opts)
              :throw false}))

(defn send-email!
  "Send email
  Takes a map with the following keys:

  * `:to` a map of `:email`, the email address of the recipient and optional `:name`
  * `:from` a map of `:email`, the email address of the sender and optional `:name`
  * `:subject` a string with the subject line
  * `:text` a string with plain text content of the email
  * `:html` a string with html content of the email

  Recipients need to confirm that they want to receive more email from you, after your first email.
  To do so they need to click on a link in a footer that gets automatically added to the first email you send to a new address.

  If you want to control the placement of the link in your email, you can use the `{{subscribe-link}}` placeholder, which will get replaced with the link before sending the email.

  Example:
  ```
  (send-email! {:to {:email \"foo@example.com\"
                     :name \"Foo Bar\"}
                :from {:email my-email-address
                       :name \"My App\"}
                :subject \"Hi!\"
                :text \"Hello World!\"
                :html \"<html><body><h1>Hello World!</h1></body></html>\"})
  ```
  In development sends mock emails that end up in `nextjournal.garden-email.mock/outbox`."
  [{:as email :keys [from to subject text html]}]
  ;; TODO support attachments
  ;; TODO block if rate-limted ?
  (let [email (update-in email [:from :email] #(or % my-email-address))]
    (m/assert validate/email-schema email)
    (let [{:keys [status body]} (if dev-mode?
                                  (mock/send-email email)
                                  (send-real-email! email))]
      (if (= 200 status)
        {:ok true :message-id body}
        {:ok false :message body}))))

(defn reply!
  "Sends an email in reply to an existing email."
  [email-to-reply-to email-to-send]
  (let [{:keys [subject from reply-to msg-id]} email-to-reply-to]
    (send-email! (merge
                  {:subject (str "Re: " subject)}
                  email-to-send
                  {:to (or reply-to from)
                   :headers {"In-Reply-To" msg-id}}))))

(defn- handle-render-email [message-id]
  (if-let [email (or (inbox message-id) (@mock/outbox message-id))]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (:html email)}
    {:status 404}))

(defn wrap-with-email
  "Ring middleware for sending and receiving email on application.garden

  Takes the ring handler and an optional map with options:
   * `:on-receive` A function to call with incoming email."
  ([f]
   (wrap-with-email f {}))
  ([f {:keys [on-receive]
       :or {on-receive save-to-inbox!}}]
   (reset! mock/on-receive f)
   (mock/wrap-with-mock-outbox
    (fn [req]
      (if-not (str/starts-with? (:uri req) "/.application.garden/garden-email")
        (f req)
        (let [path (strip-prefix "/.application.garden/garden-email" (:uri req))]
          (cond
            (= "/receive-email" path) (handle-receive on-receive req)
            (str/starts-with? path "/render-email/") (let [message-id (codec/url-decode (strip-prefix "/render-email/" path))]
                                                       (handle-render-email message-id))
            :else {:status 404})))))))
