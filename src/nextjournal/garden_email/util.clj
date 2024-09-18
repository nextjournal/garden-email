(ns nextjournal.garden-email.util
  (:require [clojure.string :as str]))

(defn parse-plus-addr [email-address]
  ;; email-address must be a RFC5322 addr-spec, i.e. without comment or name.
  (if-let [at-pos (str/last-index-of email-address "@")]
    (let [plus-pos (str/index-of email-address "+")]
      (if (and plus-pos (< plus-pos at-pos))
        {:base (subs email-address 0 plus-pos)
         :plus (subs email-address (inc plus-pos) at-pos)
         :domain (subs email-address (inc at-pos))}
        {:base (subs email-address 0 at-pos)
         :domain (subs email-address (inc at-pos))}))
    (throw (ex-info "Invalid email address" {:email-address email-address}))))

(comment
  (parse-plus-addr "foo@example.com")
  (parse-plus-addr "foo+bar@example.com")
  (parse-plus-addr "foo+bar+baz@example.com")
  (parse-plus-addr "foo@bar+baz@example+wrong.com"))
