(ns nextjournal.garden-email.render)

(defn render-email-address [{:keys [email name]}]
  (if name
    (str name "<" email ">")
    email))

(defn render-email [email-path {:keys [message-id from to subject text html]}]
  [:div {:style "display:flex;flex-direction:column;"}
   [:span [:span "From:"] (render-email-address from)]
   [:span [:span "To:"] (render-email-address to)]
   [:span [:span "Subject:"] subject]
   (if html
     [:iframe {:src (str email-path message-id)}]
     [:pre text])])

(defn render-mailbox
  ([emails]
   (render-mailbox emails {}))
  ([emails {:keys [email-path]
            :or {email-path "/.application.garden/garden-email/render-email/"}}]
   [:div.flex.flex-col.text-white
    (if (empty? emails)
      [:p.italic "empty"]
      (for [[_id email] emails]
        (render-email email-path email)))]))
