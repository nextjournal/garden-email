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
(def mock-outbox-url mock/outbox-url)

(def email-endpoint (or (System/getenv "GARDEN_EMAIL_API_ENDPOINT")
                        "https://email.application.garden"))

(def my-email-address (or (System/getenv "GARDEN_EMAIL_ADDRESS")
                          "my-email-address@example.com"))

(defn plus-address
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

(defn- handle-receive [on-receive req]
  (let [[valid? body] (read-json req)]
    (if valid?
      (do (on-receive body)
          {:status 200})
      {:status  400
       :headers {"Content-Type" "text/plain"}
       :body    "Malformed JSON in request body."})))

(def inbox-path (str (fs/path (System/getenv "GARDEN_STORAGE") ".mailbox")))

;;TODO
;; - [ ] better serialization? (maildir?)
;; - [ ] support attachments
(defn save-to-inbox! [{:as email :keys [message-id]}]
  (fs/create-dirs inbox-path)
  (spit (str (fs/path inbox-path message-id)) (pr-str email)))

(defn delete-from-inbox! [{:as email :keys [message-id]}]
  (fs/delete-if-exists (fs/path inbox-path message-id)))

(defn clear-inbox! []
  (doseq [mail (fs/list-dir inbox-path)]
    (fs/delete mail)))

(defn inbox
  ([] (map (fn [path] (edn/read-string (slurp (str path)))) (try (fs/list-dir inbox-path)
                                                                 (catch java.nio.file.NoSuchFileException e nil))))
  ([message-id] (try (edn/read-string (slurp (str (fs/path inbox-path message-id))))
                     (catch Exception _ nil))))

(defn- send-real-email! [{:as opts :keys [from to subject text html attachments]}]
  (http/post (str email-endpoint "/send")
             {:headers {"Content-Type" "application/json"
                        "Authorization" (str "Bearer " auth-token)}
              :body (json/encode opts)
              :throw false}))

(defn send-email! [{:as email :keys [from to subject text html attachments]}]
  ;; TODO block if rate-limted ?
  (let [email (update-in email [:from :email] #(or % my-email-address))]
    (m/assert validate/email-schema email)
    (let [{:keys [status body]} (if dev-mode?
                                  (mock/send-email email)
                                  (send-real-email! email))]
      (if (= 200 status)
        {:ok true :message-id body}
        {:ok false :message body}))))

(defn reply! [{:keys [subject from reply-to msg-id]} email]
  (send-email! (merge
                {:subject (str "Re: " subject)}
                email
                {:to (or reply-to from)
                 :headers {"In-Reply-To" msg-id}})))

(defn- strip-prefix [prefix s]
  (if (str/starts-with? s prefix)
    (subs s (count prefix))
    s))

(defn- handle-render-email [message-id]
  (if-let [email (or (inbox message-id) (@mock/outbox message-id))]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (:html email)}
    {:status 404}))

(defn wrap-with-email
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
