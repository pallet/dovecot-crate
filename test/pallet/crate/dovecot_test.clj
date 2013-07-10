(ns pallet.crate.dovecot-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [package-manager]]
   [pallet.api :refer [plan-fn server-spec]]
   [pallet.build-actions :refer [build-actions]]
   [pallet.crate.dovecot :as dovecot]
   [pallet.test-utils]))


(deftest format-key-test
  (is (= "a" (dovecot/format-key :a/a)))
  (is (= "a" (dovecot/format-key :a)))
  (is (= "a b" (dovecot/format-key :a/b))))

(deftest format-conf-test
  (is (= "a = b" (dovecot/format-conf {:a "b"})))
  (is (= "a = b c" (dovecot/format-conf {:a ["b" :c]})))
  (is (= "a = b\nc = d" (dovecot/format-conf {:a "b" :c "d"})))
  (is (= "x {\na = b\n}" (dovecot/format-conf {:x/x {:a "b"}})))
  (is (= "x y {\na = b\n}" (dovecot/format-conf {:x/y {:a "b"}})))
  (is (= "x y {\nv {\nb = c\nd = e\n}\n}"
         (dovecot/format-conf {:x/y {:v {:b "c" :d "e"}}}))))

(def live-runit-test-spec
  (server-spec
   :extends [(dovecot/server-spec {})]
   :phases {:install (plan-fn (package-manager :update))
            :test (plan-fn
                    ;;(dovecot/service :action :start)
                    ;; (wait-for-port-listen 5555)
                    )}))
