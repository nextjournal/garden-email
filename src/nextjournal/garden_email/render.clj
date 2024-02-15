(ns nextjournal.garden-email.render)

#_(defn render-email-address [{:keys [email name]}]
  (if name
    (str name "<" email ">")
    email))

(def render-email-address identitiy)

(defn render-email [{:keys [message-id from to subject text html]}]
  [:div {:style "display:flex;flex-direction:column;"}
   [:span [:span "From:"] (render-email-address from)]
   [:span [:span "To:"] (render-email-address to)]
   [:span [:span "Subject:"] subject]
   (if html
     [:iframe {:src (str "/.application.garden/garden-email/render-email/" message-id)}]
     [:pre text])])

(defn render-mailbox [emails]
  [:div.flex.flex-col.text-white
   (for [[_id email] emails]
     (render-email email))])
