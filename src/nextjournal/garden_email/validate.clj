(ns nextjournal.garden-email.validate
  (:require [malli.core :as m]))

(def email-address [:re #"^[^@]+@[^@]+\.[^@]+$"])
(comment
  (not (m/validate email-address "@example.com"))
  (not (m/validate email-address "foo@bar@example.com"))
  (m/validate email-address "foo@example.com"))

(def participant [:map
                  [:name {:optional true} :string]
                  [:email #'email-address]])

(def non-empty-string [:re {:error/message "String cannot be empty"} #".+"])

;;TODO improve validation
(def html [:re {:error/message "Invalid HTML"} #"</body>"])

(def email-schema (m/schema [:map
                             [:to #'participant]
                             [:from #'participant]
                             [:subject {:optional true} :string]
                             [:text {:optional true} #'non-empty-string]
                             [:html {:optional true} #'html]]))

(comment
  (require '[malli.generator :as mg])
  (mg/generate email-schema))
