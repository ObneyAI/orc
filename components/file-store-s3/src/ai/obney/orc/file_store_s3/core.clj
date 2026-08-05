(ns ai.obney.orc.file-store-s3.core
  (:require [cognitect.aws.client.api :as aws]
            [ai.obney.orc.file-store.interface.protocol :as p]
            [com.brunobonacci.mulog :as u]))

(defn start
  [{:keys [config] :as _file-store}]
  (let [base-config {:api :s3}
        client-config (if (and (:localstack/enabled config)
                               (:localstack/endpoint config))
                        (merge base-config
                               {:region (:aws/region config "us-east-1")
                                :endpoint-override {:protocol :http
                                                    :hostname "localhost"
                                                    :port 4566}})
                        (if (:aws/region config)
                          (merge base-config {:region (:aws/region config)})
                          base-config))
        client (aws/client client-config)]
    (u/trace
     ::starting-s3-file-store
     []
     (aws/validate-requests client true)
     {:s3/client client})))

(defn put-file
  [{{:keys [s3-bucket]} :config
    {:keys [s3/client]} :state}
   {:keys [file-id file-contents]}]
  (u/trace
   ::put-file
   [::file-id file-id
    ::s3-bucket s3-bucket]
   (aws/invoke client {:op :PutObject
                       :request {:Bucket s3-bucket
                                 :Key (str file-id)
                                 :Body file-contents}})))

(defn get-file
  [{{:keys [s3-bucket]} :config
    {:keys [s3/client]} :state}
   {:keys [file-id]}]
  (u/trace
   ::get-file
   [::file-id file-id
    ::s3-bucket s3-bucket]
   (let [response (aws/invoke
                   client
                   {:op :GetObject
                    :request {:Bucket s3-bucket
                              :Key (str file-id)}})]
     (.readAllBytes (:Body response)))))

(defn locate-file
  [{{:keys [s3-bucket]} :config}
   {:keys [file-id]}]
  (u/trace
   ::locate-file
   [::file-id file-id
    ::s3-bucket s3-bucket]
   {:s3/bucket s3-bucket
    :s3/key file-id}))

(defrecord S3FileStore [config]
  p/FileStore
  (start [this] (assoc this :state (start this)))
  (stop [this] this)
  (put-file [this args] (put-file this args))
  (get-file [this args] (get-file this args))
  (locate-file [this args] (locate-file this args)))

(defmethod p/start-file-store :s3
  [config]
  (p/start (->S3FileStore config)))

(comment
  ;; Example usage with LocalStack
  (def file-store
    (p/start (->S3FileStore {:s3-bucket "orc-files"
                             :localstack/enabled true
                             :localstack/endpoint "http://localhost:4566"
                             :aws/region "us-east-1"})))

  (def file-id (random-uuid))

  (p/put-file file-store
              {:file-id file-id
               :file-contents (byte-array (.getBytes "Hello, world!"))})

  (String. (p/get-file file-store {:file-id file-id}))

  "")
