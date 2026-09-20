(ns libtmux.internal.package-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [libtmux.internal.test-runner :as runner])
  (:import [clojure.lang Compiler DynamicClassLoader]
           [java.io File StringWriter]
           [java.nio.file Files]))

(deftest core-does-not-pull-the-optional-adapter
  (is (nil? (io/resource "clojure/core/async.clj"))))

(defn- with-test-sources [body]
  (let [directory (.toFile (Files/createTempDirectory "clj-runner-"
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
        property "libtmux.clojure.test-namespaces"
        previous (System/getProperty property)]
    (with-open [loader (DynamicClassLoader. (clojure.lang.RT/baseLoader))]
      (.addURL loader (.toURL (.toURI directory)))
      (try
        (doseq [[namespace test-form]
                {"runner-probe.visible-test" "(deftest pass (is true))"
                 "runner-probe.omitted-test" "(deftest fail (is false))"}]
          (let [^File source (io/file directory
                                      (str (str/replace (str/replace namespace "." "/") "-" "_") ".clj"))]
            (.mkdirs (.getParentFile source))
            (spit source (str "(ns " namespace " (:require [clojure.test :refer [deftest is]]))\n"
                              test-form))))
        (System/setProperty property "runner-probe.visible-test")
        (with-bindings {Compiler/LOADER loader}
          (body directory))
        (finally
          (if previous (System/setProperty property previous) (System/clearProperty property))
          (doseq [^File file (reverse (file-seq directory))]
            (Files/deleteIfExists (.toPath file))))))))

(defn- runner-failure [& arguments]
  (binding [test/*test-out* (StringWriter.)]
    (try
      (apply runner/-main arguments)
      nil
      (catch clojure.lang.ExceptionInfo failure failure))))

(deftest source-discovery-does-not-skip-an-omitted-failing-namespace
  (with-test-sources
    (fn [directory]
      (is (= {:fail 1 :error 0}
             (select-keys (ex-data (runner-failure "test" (str directory))) [:fail :error]))))))

(deftest source-discovery-rejects-an-empty-root
  (with-test-sources
    (fn [directory]
      (let [^File empty-root (io/file directory "empty")]
        (.mkdir empty-root)
        (is (some? (runner-failure "test" (str empty-root))))))))
