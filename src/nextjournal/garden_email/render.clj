(ns nextjournal.garden-email.render
  (:import (java.time ZonedDateTime)
           (java.time.format DateTimeFormatter DateTimeParseException)))

(defn render-email-address [{:keys [email name]}]
  (if name
    (str name " <" email ">")
    email))

(defn render-email
  "Returns Hiccup to render a single email

  Takes email-path, which when suffixed with an email's message-id gives an url which return the email's HTML content, and the email"
  [email-path {:keys [message-id from to subject date text html]}]
  [:div.flex.flex-col.bg-white.mt-5.max-w-screen-lg
   [:div.flex.flex-col.bg-slate-100.text-lg.p-5.w-full
    [:div [:span.pr-2 "From:"] (render-email-address from)]
    [:div [:span.pr-2 "To:"] (render-email-address to)]
    [:div [:span.pr-2 "Subject:"] subject]
    [:div [:span.pr-2 "Date:"] (str date)]]
   (if html
     [:iframe.bg-white-100 {:sandbox "allow-same-origin"
                            :onload "this.style.height=(this.contentWindow.document.body.scrollHeight+40)+'px';"
                            :src (str email-path message-id)}]
     [:pre text])])

(defn guard [p f] (fn [x] (when (p x) (f x))))

(def email-date-format (DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm:ss zzz"))

(defn parse-email-date [datetime]
  (try
    (ZonedDateTime/parse datetime email-date-format)
    (catch DateTimeParseException _ nil)))

(defn render-mailbox
  "Returns Hiccup to render a list of emails.

  Takes a list of emails and an optional map with options:
  * `:email-path` A url, which when suffixed with an email's message-id gives an url which return the email's HTML content"
  ([emails]
   (render-mailbox emails {}))
  ([emails {:keys [email-path]
            :or {email-path "/.application.garden/garden-email/render-email/"}}]
   [:div.flex.flex-col
    (if (empty? emails)
      [:p.italic "empty"]
      (into [:div]
            (map (partial render-email email-path))
            (sort-by (comp (guard string? parse-email-date) :date)
                     #(try (.compareTo %2 %1) (catch Exception _ (if (nil? %1) 1 -1)))
                     (vals emails))))]))
