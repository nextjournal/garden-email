(ns nextjournal.garden-email.mock
  (:require [huff.core :as h]
            [nextjournal.garden-email.render :as render]))


(defonce outbox (atom {}))
(defonce on-receive (atom nil))

(defn- mock-email-http-response [_data]
  {:status 200})

(defn- ok-email-respones? [{:keys [status]}]
  (#{202 200} status))

(defn send-email [{:as email :keys [from to subject text html attachments]}]
  (let [message-id (str "<" (random-uuid) "@nextjournal.com>")
        data (assoc email :message-id message-id)
        response (mock-email-http-response data)]
    (when (ok-email-respones? response)
      (swap! outbox assoc message-id data))
    response))

(defn receive-email [{:as email :keys [message-id from to subject text html attachments]}]
  (if-let [on-receive @on-receive]
    (on-receive email)
    (throw (ex-info "No on-receive hook configured. Did you forget to wrap your app with nextjournal.garden-email/wrap-with-email?" {}))))

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
    [:body.bg-slate-950.flex.w-screen.h-screen.justify-center.items-center
     (vec
      (concat
       [:div.sm:mx-auto.sm:w-full.sm:max-w-sm
        [:div.max-w-lg.flex.justify-center.items-center.w-full.mb-6
         [:img {:src "https://cdn.nextjournal.com/data/QmTWkWW9XkFVWjnNLLyXbU3TvZXx9DuS4nTVpETQGCwRTV?filename=The-Garden.png&content-type=image/png"
                :width 100
                :height 100}]]]
       contents))]]))

(defn render-outbox []
  (->html
   [:div.flex.flex-col.text-white
    [:p "Sent emails:"]
    (render/render-mailbox @outbox)]))
