(ns libtmux.internal.core-async-package-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]))

(deftest core-async-is-on-the-runtime-classpath
  (let [channel (async/chan 1)]
    (is (true? (async/offer! channel :ready)))
    (is (= :ready (async/poll! channel)))
    (async/close! channel)))
