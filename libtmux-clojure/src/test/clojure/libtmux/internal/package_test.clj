(ns libtmux.internal.package-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [libtmux.core :as tmux]
            [libtmux.internal.api-reference :as reference]
            [libtmux.internal.test-runner :as runner])
  (:import [clojure.lang Compiler DynamicClassLoader]
           [java.io File StringWriter]
           [java.nio.file Files]))

(deftest core-does-not-pull-the-optional-adapter
  (is (nil? (io/resource "clojure/core/async.clj"))))

(deftest native-documentation-contains-functions-and-escaped-source
  (let [directory (.toFile (Files/createTempDirectory "clj-native-docs-"
                                                     (make-array java.nio.file.attribute.FileAttribute 0)))
        properties {"libtmux.clojure.reference.module" "libtmux-clojure"
                    "libtmux.clojure.main-namespaces" "libtmux.core,libtmux.data,libtmux.async,libtmux.control"}
        previous (into {} (map (fn [[key _]] [key (System/getProperty key)])) properties)
        before (meta #'tmux/open!)]
    (try
      (doseq [[key value] properties] (System/setProperty key value))
      (alter-meta! #'tmux/open! assoc :doc
                   "Escapes <script> and &. Use [[snapshot!]], [[libtmux.data/panes]], and [[libtmux.control]] with `:tmux/ref`.")
      (let [failure (try (reference/-main "html" (str directory)) nil
                         (catch clojure.lang.ExceptionInfo error error))
            index (io/file directory "index.html")]
        (is (nil? failure) (when failure (.getMessage ^Throwable failure)))
        (is (.isFile index))
        (when (.isFile index)
          (let [html (slurp index)]
            (doseq [function ["libtmux.core/open!" "libtmux.data/matching"
                              "libtmux.async/submit!" "libtmux.control/next!"]]
              (is (str/includes? html function)))
            (is (str/includes? html "&lt;script&gt;"))
            (is (not (str/includes? html "<script>")))
            (doseq [[target label] [["libtmux.core/snapshot!" "snapshot!"]
                                    ["libtmux.data/panes" "libtmux.data/panes"]
                                    ["libtmux.control" "libtmux.control"]]]
              (is (true? (str/includes? html (str "<a href=\"#" target "\"><code>" label "</code></a>")))
                  (str "Rendered docstring link: " label)))
            (is (true? (str/includes? html "<code>:tmux/ref</code>")))
            (is (true? (not (str/includes? html "[[snapshot!]]"))))
            (is (str/includes? html "source/libtmux/core.clj.html"))))
        (let [source (io/file directory "source/libtmux/core.clj.html")]
          (is (.isFile source))
          (when (.isFile source)
            (is (str/includes? (slurp source) "id=\"L1\""))
            (is (str/includes? (slurp source) "&lt;")))))
      (let [markdown (io/file directory "api.md")]
        (reference/-main "write" (str markdown))
        (is (true? (str/includes? (slurp markdown)
                                  "[`snapshot!`][api:libtmux.core/snapshot!]")))
        (is (true? (str/includes? (slurp markdown)
                                  "[api:libtmux.core/snapshot!],")))
        (is (true? (str/includes? (slurp markdown)
                                  "[api:libtmux.data/panes]:\n  #libtmuxdatapanes"))))
      (alter-meta! #'tmux/open! assoc :doc "See [[missing-public-var]].")
      (let [failure (try (reference/-main "html" (str directory)) nil
                         (catch clojure.lang.ExceptionInfo error error))]
        (is (= {:namespace 'libtmux.core :reference "missing-public-var"}
               (select-keys (ex-data failure) [:namespace :reference]))))
      (finally
        (reset-meta! #'tmux/open! before)
        (doseq [[key value] previous]
          (if value (System/setProperty key value) (System/clearProperty key)))
        (doseq [^File file (reverse (file-seq directory))]
          (Files/deleteIfExists (.toPath file)))))))

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
