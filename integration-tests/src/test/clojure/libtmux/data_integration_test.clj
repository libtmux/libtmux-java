(ns libtmux.data-integration-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [libtmux.data :as data]
            [libtmux.internal.fixture :as fixture])
  (:import [io.github.libtmux Pane_ Server ServerConfig ServerEndpoint SessionId Session_ TmuxVersion Window_]
           [io.github.libtmux.control ControlClient]
           [io.github.libtmux.query FilterExpr]
           [io.github.libtmux.transport ProcessTransport TmuxTransport]
           [java.nio.file Path]
           [java.time Duration]
           [java.util.regex Pattern]))

(set! *warn-on-reflection* true)

(defn- command! [^Server server & argv]
  (is (.succeeded (.cmd server ^java.util.List (vec argv)))))

(deftest capture-values-preserve-occurrences-and-never-read-live-state
  (fixture/with-owned-server
   (fn [{:keys [socket binary]}]
     (let [config (-> (ServerConfig/builder)
                      (.binary binary)
                      (.endpoint (ServerEndpoint/socketPath
                                  ^Path socket))
                      (.defaultTimeout (Duration/ofMillis 900))
                      .build)
           calls (atom 0)
           old (atom nil)]
       (with-open [server (Server/open config)
                   processes (ProcessTransport.)]
         (command! server "rename-session" "-t" "$0" "alpha")
         (command! server "new-session" "-d" "-s" "beta")
         (command! server "link-window" "-s" "alpha:0" "-t" "alpha:9")
         (command! server "link-window" "-s" "alpha:0" "-t" "beta:9")
         (command! server "select-window" "-t" "alpha:9")
         (with-open [client (ControlClient/attach config (SessionId. "$0"))
                     measured (Server/using
                               config
                               (reify TmuxTransport
                                 (execute [_ request]
                                   (swap! calls inc)
                                   (.execute processes request))
                                 (close [_])))]
           (let [captured (.capture measured)
                 entries (data/from-capture captured)
                 acquired @calls
                 alpha (data/exactly-one
                        (filter #(= "alpha" (:session/name %)) (:tmux/sessions entries)))
                 links (data/windows alpha)
                 panes (data/panes alpha)
                 shared (filter #(= (-> links first :window/id) (:window/id %))
                                (:tmux/windows entries))
                 detached (data/data entries)]
             (is (= 2 acquired))
             (is (= 3 (count shared)))
             (is (= 2 (count links)))
             (is (= 2 (count panes)))
             (is (data/same-entity? (first panes) (second panes)))
             (is (not (data/same-link? (first panes) (second panes))))
             (is (= 9 (:window/index (data/active-window alpha))))
             (is (= 9 (get-in (data/active-pane alpha) [:tmux/link :window/index])))
             (let [matches (data/predicate (.is (Session_/name) "alpha"))]
               (is (matches (assoc alpha :session/name "edited")))
               (is (thrown? clojure.lang.ExceptionInfo (matches (data/data alpha)))))
             (let [active (.is (Window_/active) true)
                   index-zero (.is (Window_/index) 0)
                   same-child (.any (Session_/windows) (.and ^FilterExpr active index-zero))
                   separate-children (.and ^FilterExpr (.any (Session_/windows) active)
                                           (.any (Session_/windows) index-zero))]
               (is (false? ((data/predicate same-child) alpha)))
               (is (true? ((data/predicate separate-children) alpha))))
             (doseq [[items expressions]
                     [[(:tmux/sessions entries)
                       [(FilterExpr/and []) (FilterExpr/or [])
                        (.is (Session_/attached) false)
                        (.matches (Session_/name) (Pattern/compile "LPH" Pattern/CASE_INSENSITIVE))
                        (.all (Session_/windows) (FilterExpr/and []))
                        (.none (Session_/windows) (FilterExpr/or []))]]
                      [(:tmux/windows entries)
                       [(.is (Window_/session) (.is (Session_/name) "alpha"))
                        (.any (Window_/panes) (.is (Pane_/active) true))]]
                      [(:tmux/panes entries)
                       [(.is (Pane_/active) false) (.greaterThan (Pane_/width) 0)]]]
                     ^FilterExpr expression expressions]
               (let [duplicates (into (vec items) (take 1 items))
                     direct (filterv #(.test expression %) (mapv :tmux/ref duplicates))
                     lazy-result (mapv :tmux/ref (data/matching expression duplicates))
                     reduced-result (into [] (comp (data/matching expression) (map :tmux/ref)) duplicates)]
                 (is (= direct lazy-result reduced-result))
                 (is (every? true? (map identical? direct reduced-result)))))
             (is (thrown? IllegalArgumentException (.is (Session_/name) nil)))
             (is (every? (if (.atLeast (TmuxVersion/parse (:tmux/version entries))
                                      (TmuxVersion. 3 7 ""))
                           false? nil?)
                         (map :pane/floating? (:tmux/panes entries))))
             (is (seq (:tmux/clients entries)))
             (is (= "$0" (get-in entries [:tmux/clients 0 :client/session :session/id])))
             (is (= detached (edn/read-string (pr-str detached))))
             (dotimes [_ 20]
               (hash entries)
               (pr-str entries)
               (= entries entries)
               (into [] (comp (filter :pane/active?) (map :pane/id)) (data/panes alpha))
               (group-by :window/id (:tmux/windows entries)))
             (command! server "rename-window" "-t" "alpha:0" "new-name")
             (is (not= "new-name" (:window/name (first links))))
             (is (= detached (data/data entries)))
             (is (= acquired @calls))
             (reset! old entries)))
         (is (= (data/data @old) (edn/read-string (pr-str (data/data @old)))))
         (is (thrown? IllegalStateException
                      (.capture ^Server (:tmux/ref @old)))))))))
