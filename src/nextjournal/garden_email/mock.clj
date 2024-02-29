(ns nextjournal.garden-email.mock
  (:require [huff.core :as h]
            [ring.util.codec :as codec]
            [nextjournal.garden-email.render :as render]
            [nextjournal.garden-email.footer :as footer]
            [clojure.string :as str]))


(defonce notification-statuses (atom {}))

(defn notification-status [email-address]
  (@notification-statuses email-address))

(def ^:private host "http://localhost:7777")
(def ^:private path-prefix "/.application.garden/garden-email/mock/")

(defn confirmation-link [email-address]
  (str host path-prefix "confirm/" email-address))
(defn block-link [email-address]
  (str host path-prefix "block/" email-address))
(defn report-spam-link [email-address]
  (str host path-prefix "report-spam/" email-address))

(defonce outbox (atom {}))
(defonce on-receive (atom nil))

(defn clear-outbox! []
  (reset! outbox {}))

(defn send-email [{:as email :keys [from to subject text html attachments]}]
  (let [message-id (str "<" (random-uuid) "@nextjournal.com>")
        recipient-address (:email to)
        notification-status (notification-status recipient-address)
        transformed-email (footer/transform-email notification-status
                                                  {:subscribe-link (confirmation-link recipient-address)
                                                   :block-link (block-link recipient-address)
                                                   :report-spam-link (report-spam-link recipient-address)}
                                                  email)
        data (assoc transformed-email :message-id message-id :date (java.time.Instant/now))]
    (if (#{:notification.status/pending :notification.status/blocked} notification-status)
      {:status 403 :body "You are not allowed to send emails to the recipient. The recipient needs to allow sending"}
      (do (swap! outbox assoc message-id data)
          (when (nil? notification-status)
            (swap! notification-statuses assoc recipient-address :notification.status/pending))
          {:status 200
           :body message-id}))))

(defn receive-email [{:as email :keys [message-id from to subject text html attachments]}]
  (if-let [on-receive @on-receive]
    (on-receive email)
    (throw (ex-info "No on-receive hook configured. Did you forget to wrap your app with `nextjournal.garden-email/wrap-with-email`?" {}))))

(def ^:private tw-config
  "tailwind.config = { theme: {fontFamily: { sans: [\"Fira Sans\", \"-apple-system\", \"BlinkMacSystemFont\", \"sans-serif\"], serif: [\"PT Serif\", \"serif\"], mono: [\"Fira Mono\", \"monospace\"] } } }")

(defn- ->html [& contents]
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
                 :height 100}]]
     contents)]))

(defn render-outbox []
  [:div.flex.flex-col.justify-center.items-center
   [:a.m-2.p-2.rounded.border.text-white.w-fit {:href "clear/"} "Clear outbox"]
   [:p.m-2.text-white.text-center "Sent emails:"]
   (render/render-mailbox @outbox)])

(defn- strip-prefix [prefix s]
  (if (str/starts-with? s prefix)
    (subs s (count prefix))
    s))

(defn html-response [& contents]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (apply ->html contents)})

(defn redirect [to]
  {:status 302
   :headers {"location" to}})

(def outbox-url (str "http://localhost:7777" path-prefix "outbox/"))

(defn wrap-with-mock-outbox [app]
  (fn [{:as req :keys [uri]}]
    (if-not (str/starts-with? uri path-prefix)
      (app req)
      (let [path (strip-prefix path-prefix uri)]
        (cond
          (= "outbox/" path) (html-response (render-outbox))
          (= "outbox/clear/" path) (do (clear-outbox!)
                                       (redirect "/.application.garden/garden-email/mock/outbox/"))
          (str/starts-with? path "confirm/") (let [email-address (codec/url-decode (strip-prefix "confirm/" path))]
                                               (swap! notification-statuses assoc email-address :notification.status/subscribed)
                                               (html-response
                                                [:div.bg-slate-100.rounded.p-5
                                                 [:p "Success. The app will be able to send you emails now."]]))
          (str/starts-with? path "block/") (let [email-address (codec/url-decode (strip-prefix "block/" path))]
                                             (swap! notification-statuses assoc email-address :notification.status/blocked)
                                             (html-response
                                              [:p "Success. The app will no longer be able to send you emails now."]))
          (str/starts-with? path "report-spam/") (let [email-address (codec/url-decode (strip-prefix "subscribe/" path))]
                                                   (swap! notification-statuses assoc email-address :notification.status/blocked)
                                                   (html-response
                                                    [:div.bg-slate-100.rounded.p-5
                                                     [:p "Thank you! We'll look into this. The app will no longer be able to send you emails."]]))
          :else {:status 404})))))
