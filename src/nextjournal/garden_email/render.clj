(ns nextjournal.garden-email.render)

(defn render-email-address [{:keys [email name]}]
  (if name
    (str name " <" email ">")
    email))

(defn render-email [email-path {:keys [message-id from to subject date text html]}]
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

(defn render-mailbox
  ([emails]
   (render-mailbox emails {}))
  ([emails {:keys [email-path]
            :or {email-path "/.application.garden/garden-email/render-email/"}}]
   [:div.flex.flex-col 
    (if (empty? emails)
      [:p.italic "empty"]
      (for [email (sort-by :date (vals emails))]
        (render-email email-path email)))]))
