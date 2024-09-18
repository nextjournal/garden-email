(ns nextjournal.garden-email.templates
  (:require [clojure.string :as str]
            [huff.core :as h]))

(defn initial-txt-email [{:keys [subscribe-link]} {:as _email :keys [from]}]
  (let [{:keys [name email]} from
        sender (or name email)]
    (str/join "\n" [(format "%s would like to send you email." sender)
                    (format "If you want to receive email from %s, please go to:" sender)
                    subscribe-link
                    "You can always unsubscribe later."
                    "If you do not want to receive any more email, you can ignore this message."])))

(defn initial-html-email [{:keys [subscribe-link]} {:as _email :keys [from]}]
  (let [{:keys [name email]} from
        sender (or name email)]
    (h/html
     [:div
      [:p (format "%s would like to send you email." sender)]
      [:p (format "Please confirm that you want to receive email from %s." sender)]
      [:a {:href subscribe-link
           :style "color:white;background:rgba(146, 189, 154, 1);padding:10px;display:block;text-decoration:none;width:fit-content;margin:auto"} "Allow email"]
      [:p "You can always unsubscribe later. If you do not want to receive any more email, you can ignore this message."]])))

(defn initial-email [placeholders email]
  (let [sender (or (-> email :from :name)
                   (-> email :from :email))]
    (assoc email
           ;;TODO better subject
           :subject (format "Receiving Email from %s" sender)
           :text (initial-txt-email placeholders email)
           :html (initial-html-email placeholders email))))

(defn txt-footer-subscribed [{:keys [block-link report-spam-link]} {:as _email :keys [from]}]
  (str/join "\n" [(format "This email was sent by https://application.garden on behalf of %s." (or (:name from) (:email from)))
                  (format "To unsubscribe from emails by %s, go to:" (or (:name from) (:email from)))
                  block-link
                  "To report this email as spam, go to:"
                  report-spam-link]))


(def footer-style {:style {:color "#666"
                           :font-size 16
                           :border-top "2px"}})

(defn html-footer-subscribed [{:keys [block-link report-spam-link]} {:as _email :keys [from]}]
  (h/html
   [:div footer-style
    [:p "This email was sent by " [:a {:href "https://application.garden" :style {:color "#666"}} "application.garden"] " on behalf of " (or (:name from) (:email from)) "."]
    [:a {:href block-link :style {:color "#666" :padding "2px"}} "Unsubscribe"]
    [:a {:href report-spam-link :style {:color "#666" :padding "2px"}} "Report as Spam"]]))

(defn add-txt-footer [email-text footer]
  (str email-text "\n\n" (apply str (repeat 80 "-")) "\n" footer))

(defn add-html-footer [email-html footer]
  (str/replace email-html "</body>" (str "<hr>" footer "</body>")))

(defn add-footer [placeholders {:as email :keys [text html]}]
  (if (or text html)
    (cond-> email
      text (update :text add-txt-footer (txt-footer-subscribed placeholders email))
      html (update :html add-html-footer (html-footer-subscribed placeholders email)))
    (assoc email :text (txt-footer-subscribed placeholders email))))

(defn transform-email [placeholders email]
  (->> email
       (add-footer placeholders)))
