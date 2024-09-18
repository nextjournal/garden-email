(ns nextjournal.garden-email.shared
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [huff.core :as h]
            [malli.core :as m]
            [malli.error :as me]
            [nextjournal.garden-email.rate-limit :as rate-limit]
            [nextjournal.garden-email.templates :as templates]
            [nextjournal.garden-email.util :refer [parse-plus-addr]]
            [nextjournal.garden-email.validate :as email-valid]
            [reitit.core :as r]
            [ring.util.request :refer [character-encoding]]
            [ring.util.response :refer [response content-type redirect]])
  (:import [java.io InputStream]
           [java.util.concurrent TimeUnit]))

(defn project-email-address [project-name]
  (str project-name "@" "apps.garden"))

(defn sender-allowed? [project-name sender-address]
  (let [{:keys [base domain]} (parse-plus-addr sender-address)]
    (= (project-email-address project-name) (str base "@" domain))))

(def ^:private tw-config
  "tailwind.config = { theme: {fontFamily: { sans: [\"Fira Sans\", \"-apple-system\", \"BlinkMacSystemFont\", \"sans-serif\"], serif: [\"PT Serif\", \"serif\"], mono: [\"Fira Mono\", \"monospace\"] } } }")

(defn- page [& contents]
  (h/html
   {:allow-raw true}
   [:html
    [:head
     [:meta {:charset "UTF-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:link {:rel "preconnect" :href "https://fonts.bunny.net"}]
     [:link {:rel "stylesheet" :href "https://fonts.bunny.net/css?family=fira-mono:400,700%7Cfira-sans:400,400i,500,500i,700,700i%7Cfira-sans-condensed:700,700i%7Cpt-serif:400,400i,700,700i"}]
     [:script {:type "text/javascript" :src "https://cdn.tailwindcss.com?plugins=typography"}]
     [:script [:hiccup/raw-html tw-config]]]
    (into
     [:body.bg-slate-950.flex.flex-col.w-screen.justify-center.items-center
      [:img.m-5 {:src "https://cdn.nextjournal.com/data/QmTWkWW9XkFVWjnNLLyXbU3TvZXx9DuS4nTVpETQGCwRTV?filename=The-Garden.png&content-type=image/png"
                 :width 100
                 :height 100}]
      contents])]))

(defn html-response [& contents]
  (-> (response (apply page contents))
      (content-type "text/html")))

(defn browser? [{:keys [headers]}]
  (some (partial str/includes? (headers "user-agent")) #{"Mozilla"}))

(defn confirmation-link [host router token]
  (str host
       (-> (r/match-by-name router ::confirm {:token token})
           (r/match->path))))
(defn block-link [host router token]
  (str host
       (-> (r/match-by-name router ::block {:token token})
           (r/match->path))))
(defn report-spam-link [host router token]
  (str host
       (-> (r/match-by-name router ::report-spam {:token token})
           (r/match->path))))

(defn- strip-prefix [prefix s]
  (if (str/starts-with? s prefix)
    (subs s (count prefix))
    s))

(defn auth-token [{:as _req :keys [headers]}]
  (strip-prefix "Bearer " (headers "authorization")))

(defn- json-request? [request]
  (when-let [type (get-in request [:headers "content-type"])]
    (some? (re-find #"^application/(.+\+)?json" type))))

(defn read-json [request]
  (when (json-request? request)
    (when-let [^InputStream body (:body request)]
      (let [^String encoding (or (character-encoding request)
                                 "UTF-8")
            body-reader (java.io.InputStreamReader. body encoding)]
        (try
          [true (json/parse-stream body-reader keyword)]
          (catch com.fasterxml.jackson.core.JsonParseException _
            [false nil]))))))

(def limited? (rate-limit/make-limiter {:limit 10
                                        :interval 1
                                        :interval-time-unit TimeUnit/HOURS}))

(defn send-project-email!
  "Send email from a project
  Adds footer with unsubscribe links"
  [{:keys [host create-token send!]} router project-id email]
  (let [recipient-addr (-> email :to :email)]
    (->> email
         (templates/add-footer {:block-link (block-link host router (create-token {:project-id project-id
                                                                              :email recipient-addr}))
                                :report-spam-link (report-spam-link host router (create-token {:project-id project-id
                                                                                               :email recipient-addr}))})
         (send!))))

(defn handle-send! [{:keys [host
                            create-token
                            parse-token
                            project-name
                            notification-status
                            set-notification-status!
                            send!
                            buffer-email!]}
                    {:as req ::r/keys [router]}]
  (try
    (let [{:keys [type project-id]} (parse-token (auth-token req))]
      (if (and (= :project-token type) project-id)
        (let [project-name (project-name project-id)
              [valid-json? {:as email :keys [from to]}] (read-json req)]
          (when-not valid-json?
            (throw (ex-info "Invalid JSON" {:status 400})))
          (when-not (m/validate email-valid/email-schema email)
            (throw (ex-info (->> (m/explain email-valid/email-schema email)
                                 (me/humanize)
                                 vals
                                 flatten
                                 (str/join "\n")) {:status 400})))
          (when-not (sender-allowed? project-name (:email from))
            (throw (ex-info (str "You are only allowed to send email from " (project-email-address project-name)) {:status 403})))
          (let [recipient-addr (-> to :email)
                notification-status (notification-status project-id recipient-addr)]
            (cond
              ;; initial email
              (nil? notification-status)
              (if-let [message-id (send! (templates/initial-email
                                               {:subscribe-link (confirmation-link host router (create-token {:project-id project-id
                                                                                                              :email recipient-addr}))}
                                               email))]
                (do
                  (buffer-email! project-id email)
                  (set-notification-status! project-id recipient-addr :notification.status/pending)
                  {:status 200 :body message-id})
                (throw (ex-info "There was an error sending your email" {:status 500})))

              ;; blocked
              (#{:notification.status/pending :notification.status/blocked} notification-status)
              (throw (ex-info "You are not allowed to send emails to the recipient. The recipient needs to allow sending" {:status 403}))

              ;; subscribed
              (#{:notification.status/subscribed} notification-status)
              (let [{:keys [limited? retry-after]} (limited? {:project-id project-id
                                                              :recipient recipient-addr})]
                (when limited?
                  (throw (ex-info "Rate-limit reached" {:status 429
                                                        :headers {"Retry-After" retry-after}})))
                (if-let [message-id (send-project-email! {:host host
                                                          :create-token create-token
                                                          :send! send!} router project-id email)]
                  {:status 200 :body message-id}
                  (throw (ex-info "There was an error sending your email" {:status 500})))))))
        (throw (ex-info "Unauthorized" {:status 403}))))
    (catch clojure.lang.ExceptionInfo e
      (throw e)
      (assoc (ex-data e) :body (ex-message e)))))

(defn routes [{:keys [host
                      create-token
                      parse-token
                      project-name
                      buffer-email!
                      buffered-email
                      notification-status
                      set-notification-status!
                      send!]}]
  [["/send" {:post (partial handle-send! {:host host
                                          :project-name project-name
                                          :create-token create-token
                                          :parse-token parse-token
                                          :notification-status notification-status
                                          :set-notification-status! set-notification-status!
                                          :send! send!
                                          :buffer-email! buffer-email!})
             :name :email/send}]
   ["/confirm/:token"
    ["/" {:name ::confirm
          :get  (fn [{:as req :keys [path-params] ::r/keys [router]}]
                  (let [{:keys [project-id]} (parse-token (:token path-params))
                        project-name (project-name project-id)
                        sender-address (project-email-address project-name)
                        href-ok (-> router
                                    (r/match-by-name ::confirm-ok path-params)
                                    (r/match->path))]
                    (if (browser? req)
                      (redirect href-ok)
                      (html-response
                       [:div.bg-slate-100.rounded.p-5
                        [:p (format "Do you want to receive email from %s in the future?" sender-address)]
                        [:div.flex
                         [:a.block.p-2.m-2.rounded.bg-emerald-700.text-slate-100 {:href href-ok} "Yes"]
                         [:span.block.p-2.m-2.rounded {:on-click "window.close"} "No"]]]))))}]
    ["/ok" {:name ::confirm-ok
            :get (fn [{:keys [path-params] ::r/keys [router]}]
                   (let [{:keys [project-id email]} (parse-token (:token path-params))
                         project-name (project-name project-id)
                         sender-address (project-email-address project-name)]
                     (set-notification-status! project-id email :notification.status/subscribed)
                     (send-project-email! {:host host
                                           :create-token create-token
                                           :send! send!} router project-id (buffered-email project-id email))
                     (html-response
                      [:div.bg-slate-100.rounded.p-5
                       [:p (format "Success. %s will be able to send you emails now." sender-address)]])))}]]
   ["/block/:token"
    ["/" {:name ::block
          :get  (fn [{:as req :keys [path-params] ::r/keys [router]}]
                  (let [{:keys [project-id]} (parse-token (:token path-params))
                        project-name (project-name project-id)
                        sender-address (project-email-address project-name)
                        href-ok (-> router
                                    (r/match-by-name ::block-ok path-params)
                                    (r/match->path))]
                    (if (browser? req)
                      (redirect href-ok)
                      (html-response
                       [:div.bg-slate-100.rounded.p-5
                        [:p (format "Do you want block %s from sending you emails in the future?" sender-address)]
                        [:div.flex
                         [:a.block.p-2.m-2.rounded.bg-emerald-700.text-slate-100 {:href href-ok} "Block emails"]
                         [:span.block.p-2.m-2.rounded {:on-click "window.close"} "Cancel"]]]))))}]
    ["/ok" {:name ::block-ok
            :get (fn [{:keys [path-params]}]
                   (let [{:keys [project-id email]} (parse-token (:token path-params))
                         project-name (project-name project-id)
                         sender-address (project-email-address project-name)]
                     (set-notification-status! project-id email :notification.status/blocked)
                     (html-response
                      [:div.bg-slate-100.rounded.p-5
                       [:p (format "Success. %s will no longer be able to send you emails now." sender-address)]])))}]]
   ["/report-spam/:token"
    ["/" {:name ::report-spam
          :get  (fn [{:as req :keys [path-params] ::r/keys [router]}]
                  (let [{:keys [project-id]} (parse-token (:token path-params))
                        project-name (project-name project-id)
                        sender-address (project-email-address project-name)
                        href-ok (-> router
                                    (r/match-by-name ::report-spam-ok  path-params)
                                    (r/match->path))]
                    (if (browser? req)
                      (redirect href-ok)
                      (html-response
                       [:div.bg-slate-100.rounded.p-5
                        [:p (format "Do you want report %s as spam?" sender-address)]
                        [:div.flex
                         [:a.block.p-2.m-2.rounded.bg-emerald-700.text-slate-100 {:href href-ok} "Report spam"]
                         [:span.block.p-2.m-2.rounded {:on-click "window.close"} "Cancel"]]]))))}]
    ["/ok" {:name ::report-spam-ok
            :get (fn [{:keys [path-params]}]
                   (let [{:keys [project-id email]} (parse-token (:token path-params))
                         project-name (project-name project-id)
                         sender-address (project-email-address project-name)]
                     (set-notification-status! project-id email :notification.status/blocked)
                     (html-response
                      [:div.bg-slate-100.rounded.p-5
                       [:p (format "Thank you! We'll look into this. %s will no longer be able to send you emails." sender-address)]])))}]]])
