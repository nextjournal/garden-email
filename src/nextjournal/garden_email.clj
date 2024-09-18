(ns nextjournal.garden-email
  (:require [clojure.edn :as edn]
            [babashka.http-client :as http]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [reitit.core :as r]
            [reitit.ring :as rr]
            [nextjournal.garden-email.render :as render]
            [nextjournal.garden-email.validate :as validate]
            [nextjournal.garden-email.shared :as shared]))

;; consistent dummy uuid for local dev
(def dev-id #uuid "c8de9f02-af56-419c-bac6-91f3e96c57cb")

(def create-token json/generate-string)

(defn parse-token [token]
  (some-> token
          (json/parse-string keyword)
          (update :project-id parse-uuid)
          (update :type keyword)))

(def dev-mode? (nil? (System/getenv "GARDEN_TOKEN")))

(def auth-token (if dev-mode?
                  (create-token {:project-id dev-id :type :project-token})
                  (System/getenv "GARDEN_TOKEN")))

(def email-endpoint (System/getenv "GARDEN_API_ENDPOINT"))

(def ^:dynamic my-email-address (shared/project-email-address (System/getenv "GARDEN_PROJECT_NAME")))

(defn plus-address
  "Create a plus-address from an email-address and an extra identifier
  `(plus-address\"foo@example.com\" \"bar\") ;=> \"foo+bar@example.com\"`"
  ([plus]
   (plus-address my-email-address plus))
  ([email-address plus]
   (str/replace-first email-address "@" (str "+" plus "@"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; mock

(def host "http://localhost:7777")

(defonce notification-statuses (atom {}))
(defn set-notification-status! [_project-id recipient-address status]
  (swap! notification-statuses assoc recipient-address status))
(defn notification-status [_project-id recipient-address]
  (@notification-statuses recipient-address))

(defonce buffered-emails (atom {}))
(defn buffer-email! [_project-id email]
  (let [recipient-address (-> email :to :email)]
    (swap! buffered-emails assoc recipient-address email)))
(defn buffered-email [_project-id recipient-address]
  (@buffered-emails recipient-address))

(defonce outbox (atom {}))
(defn clear-outbox! []
  (reset! outbox {}))

(defn send! [email]
  (let [message-id (str "<" (random-uuid) "@nextjournal.com>")
        data (assoc email :message-id message-id :date (java.time.Instant/now))]
    (swap! outbox assoc message-id data)
    message-id))

(defn project-name [_project-id] (System/getenv "GARDEN_PROJECT_NAME"))

(defonce !on-receive (atom nil))

(defn receive-email
  "Emulate receiving an email in dev"
  #_ {:clj-kondo/ignore [:unused-binding]}
  [{:as email :keys [message-id from to subject text html attachments]}]
  (if-let [on-receive @!on-receive]
    (on-receive email)
    (throw (ex-info "No on-receive hook configured. Did you forget to wrap your app with `nextjournal.garden-email/wrap-with-email`?" {}))))

(defn render-outbox []
  [:div.flex.flex-col.justify-center.items-center
   [:a.m-2.p-2.rounded.border.text-white.w-fit {:href "clear"} "Clear outbox"]
   [:p.m-2.text-white.text-center "Sent emails:"]
   (render/render-mailbox @outbox)])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; receiving email

(defn- handle-receive [on-receive req]
  ;; we use the same auth token for server -> client and client -> server communication
  (if (= auth-token (shared/auth-token req))
    (let [[valid? body] (shared/read-json req)]
      (if valid?
        (do (on-receive body)
            {:status 200})
        {:status  400
         :headers {"Content-Type" "text/plain"}
         :body    "Malformed JSON in request body."}))
    {:status 403}))

(def inbox-path (str (fs/path (System/getenv "GARDEN_STORAGE") ".application.garden/garden-email/mailbox")))

;;TODO
;; - [ ] better serialization? (maildir?)
;; - [ ] support attachments
(defn save-to-inbox! [{:as email :keys [message-id]}]
  (fs/create-dirs inbox-path)
  (spit (str (fs/path inbox-path message-id)) (pr-str email)))

(defn delete-from-inbox!
  "Delete an email in the inbox. Takes email as a map, containing `:message-id`"
  [{:as _email :keys [message-id]}]
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
                                                                                        (catch java.nio.file.NoSuchFileException _ nil)))))
  ([message-id] (try (edn/read-string (slurp (str (fs/path inbox-path message-id))))
                     (catch Exception _ nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; sending email

(declare router)
(defn send-email!
  "Send email
  Takes a map with the following keys:

  * `:to` a map of `:email`, the email address of the recipient and optional `:name`
  * `:from` a map of `:email`, the email address of the sender and optional `:name`
  * `:subject` a string with the subject line
  * `:text` a string with plain text content of the email
  * `:html` a string with html content of the email

  Your first email to a new address is buffered until the recipient confirms that they want to receive email from you.

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
  In development sends mock emails that end up in `nextjournal.garden-email/outbox`."
  #_ {:clj-kondo/ignore [:unused-binding]}
  [{:as email :keys [from to subject text html]}]
  ;; TODO support attachments
  ;; TODO block if rate-limted?
  (let [email (update-in email [:from :email] #(or % my-email-address))]
    (if (m/validate validate/email-schema email)
      (let [url (if dev-mode?
                  (str host (-> (r/match-by-name router :email/send)
                                (r/match->path)))
                  (str email-endpoint "/send"))
            {:keys [status body]} (http/post url {:headers {"content-type" "application/json"
                                                            "authorization" (str "Bearer " auth-token)}
                                                  :body (json/encode email)
                                                  :throw false})]
        (if (= 200 status)
          {:ok true :message-id body}
          {:ok false :message body}))
      {:ok false :message (-> (m/explain validate/email-schema email)
                              (me/humanize))})))

;;FIXME broken
#_(defn reply!
  "Sends an email in reply to an existing email."
  [email-to-reply-to email-to-send]
  (let [{:keys [subject from reply-to msg-id]} email-to-reply-to]
    (send-email! (merge
                  {:subject (str "Re: " subject)}
                  email-to-send
                  {:to (or reply-to from)
                   :headers {"In-Reply-To" msg-id}}))))

;; routes

(defn- handle-render-email [{:keys [path-params]}]
  (let [{:keys [message-id]} path-params]
    (if-let [email (or (inbox message-id) (@outbox message-id))]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (:html email)}
      {:status 404})))

(defn- handle-render-outbox [_req]
  (shared/html-response (render-outbox)))

(defn- handle-clear-outbox [_]
  (clear-outbox!)
  {:status 302 :headers {"location" (-> (r/match-by-name router ::outbox)
                                        (r/match->path))}})

(def prefix "/.application.garden/garden-email")
(def router (rr/router [[prefix
                         (concat [["/receive" {:post #'handle-receive}]
                                  ["/render-email/:message-id" {:get #'handle-render-email}]]
                                 (when dev-mode?
                                   (concat (shared/routes {:host host
                                                           :project-name #'project-name
                                                           :create-token #'create-token
                                                           :parse-token #'parse-token
                                                           :buffer-email! #'buffer-email!
                                                           :buffered-email #'buffered-email
                                                           :notification-status #'notification-status
                                                           :set-notification-status! #'set-notification-status!
                                                           :send! #'send!})
                                           [["/outbox" {:get #'handle-render-outbox
                                                        :name ::outbox}]
                                            ["/clear" {:get #'handle-clear-outbox}]])))]]))

(def outbox-url (str host (-> (r/match-by-name router ::outbox)
                              (r/match->path))))

(def handler (rr/ring-handler router))

(defn wrap-with-email
  "Ring middleware for sending and receiving email on application.garden

  Takes the ring handler and an optional map with options:
   * `:on-receive` A function to call with incoming email."
  ([f]
   (wrap-with-email f {}))
  ([f {:keys [on-receive]
       :or {on-receive save-to-inbox!}}]
   (reset! !on-receive on-receive)
   (fn [req]
     (if-not (str/starts-with? (:uri req) prefix)
       (f req)
       (handler req)))))
