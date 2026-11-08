(ns libtmux.private-server-test
  (:require [clojure.test :refer [deftest is]]
            [libtmux.core :as tm])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- ^Path test-root []
  (let [base (Path/of "/tmp/libtmux-java-test" (make-array String 0))]
    (Files/createDirectories base (make-array FileAttribute 0))
    (Files/createTempDirectory base "clj-" (make-array FileAttribute 0))))

(defn- options [root]
  (cond-> {:root root}
    (System/getProperty "libtmux.tmux") (assoc :binary (System/getProperty "libtmux.tmux"))))

(deftest a-started-server-answers-and-close-removes-its-directory
  (let [root (test-root)
        server (tm/start! (options root))
        directory (atom nil)]
    (try
      (tm/new-session! server {:name "started"})
      (is (= ["started"] (mapv :session/name (tm/sessions! server))))
      (reset! directory (first (iterator-seq (.iterator (Files/list root)))))
      (is (some? @directory))
      (finally (tm/close! server)))
    (is (not (Files/exists @directory (make-array java.nio.file.LinkOption 0))))
    (Files/delete root)))

(deftest unknown-start-options-are-rejected-before-anything-starts
  (let [root (test-root)
        error (try (tm/start! {:root root :socket-path "/tmp/x"}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (= :tmux/validation (:tmux/error (ex-data error))))
    (is (= 0 (count (iterator-seq (.iterator (Files/list root))))))
    (Files/delete root)))
