(ns libtmux.internal.core-async-package-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [libtmux.core-async]))

(deftest resource-constructors-are-private
  (let [publics (ns-publics 'libtmux.core-async)]
    (is (not (contains? publics '->Adapter)))
    (is (not (contains? publics '->EventsPort)))))

(deftest core-async-is-on-the-runtime-classpath
  (let [channel (async/chan 1)]
    (is (true? (async/offer! channel :ready)))
    (is (= :ready (async/poll! channel)))
    (async/close! channel)))
