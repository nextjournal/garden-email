(ns nextjournal.garden-email.mock
  (:require [huff.core :as h]))


(defonce outbox (atom []))
(defonce on-receive (atom nil))

(defn send-email [{:as email :keys [from to subject html-body attachments]}]
  (swap! outbox conj email))

(defn receive-email [{:as email :keys [from to subject html-body attachments]}]
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
     (for [{:keys [from to subject html-body]} @outbox]
       [:div.outline.rounded-md.flex.flex-col
        [:span "From:" from]
        [:span "To:" to]
        [:span "Subject:" subject]
        (when html-body {:hiccup/raw-html html-body})])]))
