(ns nextjournal.garden-email.footer
  (:require [clojure.string :as str]
            [huff.core :as h]))

(defn replace-placeholder [{:keys [subscribe-link]} {:as email :keys [text html]}]
  (cond-> email
    text (update :text #(str/replace % "{{subscribe-link}}" subscribe-link))
    html (update :html #(str/replace % "{{subscribe-link}}" subscribe-link))))

(defn txt-footer-pending [{:keys [subscribe-link]} {:as email :keys [from]}]
  (let [{:keys [name email]} from]
    (str/join "\n" [(format "This email was sent by https://application.garden on behalf of %s." (or name email))
                    (format "If you want to continue to receive emails from %s in the future," (or name email))
                    "please go to:"
                    subscribe-link
                    "You can always unsubscribe later."
                    "If you do not click the link, we will not send you any more email."])))

(defn txt-footer-subscribed [{:keys [block-link report-spam-link]} {:as email :keys [from]}]
  (str/join "\n" [(format "This email was sent by https://application.garden on behalf of %s." (or (:name from) (:email from)))
                  (format "To unsubscribe from emails by %s, go to:" (or (:name from) (:email from)))
                  block-link
                  "To report this email as spam, go to: %s"
                  report-spam-link]))


(def footer-style {:style {:color "#666"
                           :font-size 16
                           :border-top "2px"}})

(defn html-footer-pending [{:keys [subscribe-link]} {:as email :keys [from]}]
  (h/html
   [:div footer-style
    [:p "This email was sent by " [:a {:href "https://application.garden" :style {:color "#666"}} "application.garden"] " on behalf of " (or (:name from) (:email from)) "."]
    [:p "If you want to continue to receive emails from application.garden in the future, click " [:a {:href subscribe-link :style {:color "#666"}} "here"]
     ". You can always unsubscribe later. If you do not click the link, we will not send you any more email."]]))

(defn html-footer-subscribed [{:keys [block-link report-spam-link]} {:keys [project-id from to]}]
  (h/html
   [:div footer-style
    [:p "This email was sent by " [:a {:href "https://application.garden" :style {:color "#666"}} "application.garden"] " on behalf of " (or (:name from) (:email from)) "."]
    [:a {:href block-link :style {:color "#666" :padding "2px"}} "Unsubscribe"]
    [:a {:href report-spam-link :style {:color "#666" :padding "2px"}} "Report as Spam"]]))

(defn add-txt-footer [email-text footer]
  (str email-text "\n\n" (apply str (repeat 80 "-")) "\n" footer))

(defn add-html-footer [email-html footer]
  (str/replace email-html "</body>" (str footer "</body>")))

(defn txt-footer-fn [notification-status]
  (if (= :notification.status/subscribed notification-status)
    txt-footer-subscribed
    txt-footer-pending))

(defn html-footer-fn [notification-status]
  (if (= :notification.status/subscribed notification-status)
    html-footer-subscribed
    html-footer-pending))

(defn add-footer [notification-status placeholders {:as email :keys [text html]}]
  (if (or text html)
    (cond-> email
      text (update :text add-txt-footer ((txt-footer-fn notification-status) placeholders email))
      html (update :html add-html-footer ((html-footer-fn notification-status) placeholders email)))
    (assoc email :text ((txt-footer-fn notification-status) placeholders email))))

(defn transform-email [notification-status placeholders email]
  (->> email
       (add-footer notification-status placeholders)
       (replace-placeholder placeholders)))
