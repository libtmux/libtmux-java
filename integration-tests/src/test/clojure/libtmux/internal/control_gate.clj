(ns libtmux.internal.control-gate
  "Control needs tmux 3.7 or newer. Control examples run there, and every
  older daemon must refuse before attaching a client."
  (:require [clojure.test :refer [is]]
            [libtmux.control :as control]
            [libtmux.core :as tmux]
            [libtmux.internal.fixture :as fixture])
  (:import [io.github.libtmux Server]
           [io.github.libtmux.internal ControlAcquisition]))

(defn supported?
  "Whether the server's daemon qualifies for control: tmux 3.7 or newer, where a development or
  candidate build of 3.7 itself ranks below it."
  [^Server server]
  (ControlAcquisition/supports (.version server)))

(defn refused!
  "Asserts the refusal an older daemon gives, and that no client was attached."
  [server]
  (let [failure (try (control/attach! (:tmux/ref (first (tmux/sessions! server))))
                     nil
                     (catch clojure.lang.ExceptionInfo error error))]
    (is (= :tmux/unsupported-feature (:tmux/error (ex-data failure))))
    (is (= :not-dispatched (:tmux/dispatch (ex-data failure))))
    (is (empty? (.clients ^Server server)))))

(defn with-control-server
  "Runs f on a daemon that supports control, and asserts the refusal on any older one."
  [f]
  (fixture/with-owned-server
   (fn [{:keys [server] :as context}]
     (if (supported? server) (f context) (refused! server)))))
