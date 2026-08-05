(ns ai.obney.orc.file-store.interface.protocol)

(defmulti start-file-store :type)

(defprotocol FileStore
  (start [this])
  (stop [this])
  (put-file [this args])
  (get-file [this args])
  (locate-file [this args]))
