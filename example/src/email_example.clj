(ns email-example
  (:require [nextjournal.garden-email :as garden-email]
            [nextjournal.garden-email.render :as render]
            [org.httpkit.server :as httpkit]
            [ring.middleware.params :as ring.params]
            [huff.core :as h]
            [clojure.string :as str]))

(defn render-form []
  [:div
   [:h1 "Send email"]
   [:form {:action "/send" :method "POST" :style "display:flex;flex-direction:column;"}
    [:label {:for "to"} "to"]
    [:input {:name "to" :type "email"}]
    [:label {:for "subject"} "subject"]
    [:input {:name "subject" :type "text"}]
    [:label {:for "text"} "plain text"]
    [:textarea {:name "text"}]
    [:label {:for "html"} "html email"]
    [:textarea {:name "html"}]
    [:input {:type "submit" :value "send"}]]])

(defn render-inbox []
  [:div
   [:h1 "Inbox (" garden-email/my-email-address ")"]
   (render/render-mailbox (garden-email/inbox))])

(defn html-response [& contents]
  {:status 200
   :headers {"content-type" "text/html"}
   :body (h/html
          {:allow-raw true}
          [:html
           [:head
            [:meta {:charset "UTF-8"}]
            [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]]
           (vec (concat [:body]
                        contents))])})

(defn render-navigation []
  [:ul
   [:li [:a {:href "send"} "send email"]]
   [:li [:a {:href "inbox"} "inbox"]]
   (when garden-email/dev-mode? [:li [:a {:href garden-email/outbox-url} "dev outbox"]])])

(defn page [& contents]
  (vec (concat [:div
                (render-navigation)]
               contents)))

(defn app [req]
  (case (:uri req)
    "/" {:status 302 :headers {"Location" "/send"}}
    "/send" (case (:request-method req)
              :get (html-response (page (render-form)))
              :post (let [{:strs [to subject text html]} (:form-params req)]
                      (garden-email/send-email! (cond-> {:to {:email to}
                                                         :subject subject}
                                                  (not (str/blank? text)) (assoc :text text)
                                                  (not (str/blank? html)) (assoc :html html)))
                      {:status 302
                       :headers {"Location" "/send"}})
              {:status 405})
    "/inbox" (html-response (page (render-inbox)))

    {:status 404 :body "not found"}))

(defn app' [req]
  ((-> #'app
       (ring.params/wrap-params)
       (garden-email/wrap-with-email)) req))

(defn start! [opts]
  (httpkit/run-server #'app'
                      (merge {:legacy-return-value? false :port 7777}
                             opts))
  (println "started."))

(comment
  (start! nil))

(when (= *file* (System/getProperty "babashka.file"))
  (start! {})
  @(promise))
