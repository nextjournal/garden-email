(ns nextjournal.garden-email
  (:require [clojure.edn :as edn]
            [babashka.http-client :as http]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [ring.util.request :refer [character-encoding]]
            [ring.util.codec :as codec]
            [nextjournal.garden-email.mock :as mock]
            [clojure.string :as str])
  (:import [java.io InputStream]))

(def auth-token (System/getenv "GARDEN_TOKEN"))
(def dev-mode? (nil? auth-token))

(def email-endpoint (or (System/getenv "GARDEN_EMAIL_API_ENDPOINT")
                        "https://email.application.garden"))

(def my-email-address (System/getenv "GARDEN_EMAIL_ADDRESS"))


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
              :body (json/encode opts)}))

;TODO validate (malli?)
(defn send-email! [{:as opts :keys [from to subject text html attachments]}]
  (let [opts (merge {:from {:email my-email-address}} opts)]
    (if dev-mode?
      (mock/send-email opts)
      (send-real-email! opts))))

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

(defn- handle-render-email [{:keys [uri]}]
  (let [message-id (codec/url-decode (strip-prefix "/.application.garden/render-email/" uri))]
    (if-let [email (or (inbox message-id) (@mock/outbox message-id))]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (:html email)}
      {:status 404})))

(defn wrap-with-email
  ([f]
   (wrap-with-email f {}))
  ([f {:keys [on-receive]
       :or {on-receive save-to-inbox!}}]
   (reset! mock/on-receive f)
   (fn [req]
     (cond
       (= "/.application.garden/receive-email" (:uri req)) (handle-receive on-receive req)
       (and dev-mode? (= "/.application.garden/outbox" (:uri req))) {:status 200
                                                                     :headers {"Content-Type" "text/html"}
                                                                     :body (mock/render-outbox)}
       (str/starts-with? (:uri req) "/.application.garden/render-email/") (handle-render-email req)
       :else (f req)))))
