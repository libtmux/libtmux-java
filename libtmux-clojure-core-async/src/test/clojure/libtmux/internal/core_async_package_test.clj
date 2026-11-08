(ns libtmux.internal.core-async-package-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [libtmux.core-async]
            [libtmux.internal.api-reference :as reference])
  (:import [java.io File]
           [java.nio.file Files]))

(deftest native-reference-keeps-dependency-links-offline
  (let [directory (.toFile (Files/createTempDirectory "clj-adapter-docs-"
                                                     (make-array java.nio.file.attribute.FileAttribute 0)))
        properties {"libtmux.clojure.reference.module" "libtmux-clojure-core-async"
                    "libtmux.clojure.main-namespaces" "libtmux.core-async"}
        previous (into {} (map (fn [[key _]] [key (System/getProperty key)])) properties)]
    (try
      (doseq [[key value] properties] (System/setProperty key value))
      (reference/-main "write" (str (io/file directory "api.md")))
      (is (true? (str/includes? (slurp (io/file directory "api.md"))
                                "[api:libtmux.control/attach!]:\n  clojure-api.md#libtmuxcontrolattach")))
      (reference/-main "html" (str directory))
      (let [html (slurp (io/file directory "index.html"))]
        (is (true? (str/includes? html "id=\"libtmux.control/attach!\"")))
        (is (true? (str/includes? html "id=\"libtmux.data/panes\"")))
        (is (.isFile (io/file directory "source/libtmux/control.clj.html"))))
      (finally
        (doseq [[key value] previous]
          (if value (System/setProperty key value) (System/clearProperty key)))
        (doseq [^File file (reverse (file-seq directory))]
          (Files/deleteIfExists (.toPath file)))))))

(deftest resource-constructors-are-private
  (let [publics (ns-publics 'libtmux.core-async)]
    (is (not (contains? publics '->Adapter)))
    (is (not (contains? publics '->EventsPort)))))

(deftest core-async-is-on-the-runtime-classpath
  (let [channel (async/chan 1)]
    (is (true? (async/offer! channel :ready)))
    (is (= :ready (async/poll! channel)))
    (async/close! channel)))
