(ns osm
  "OpenStreetMap data set experiment"
  (:require [clojure.data.xml :as xml]
   [clojure.java.io :as io]
   [datomic.api :as d]
   [datomic.cache :as cache]
   [datomic.domain :as domain])
  (:import com.uber.h3core.H3Core))

(defn altmod
  "reduction of an integer x into a space [0,max], but faster than (mod x max)
   Both args must be less than 2^32.

   Make sure x is sufficiently random before calling this, or use (-> x mix64 (altmod max))
   https://lemire.me/blog/2016/06/27/a-fast-alternative-to-the-modulo-reduction/"
  ^long [^long x ^long max]
  (-> (bit-and x 0xffffffff)
    (unchecked-multiply max)
    (unsigned-bit-shift-right 32)))

(def ^:const m1 (unchecked-long 0xbf58476d1ce4e5b9))
(def ^:const m2 (unchecked-long 0x94d049bb133111eb))

;; the mixing step from SplittableRandom
;; https://github.com/openjdk/jdk/blob/83d77b1cbb6d0179e9c130d51b7fada2e76e86d3/src/java.base/share/classes/java/util/SplittableRandom.java#L188-L196
;; https://zimbry.blogspot.com/2011/09/better-bit-mixing-improving-on.html
(defn mix64
  "bitmixer, take an intermediate hash value that may not be thoroughly
  mixed and increase its entropy to obtain both better distribution
  and fewer collisions among hashes. Small differences in input
  values, as you might get when hashing nearly identical data, should
  result in large differences in output values after mixing."
  ^long [^long x]
  (let [r1 (-> (bit-xor x (unsigned-bit-shift-right x 30))
             (unchecked-multiply m1))
        r2 (-> (bit-xor r1 (unsigned-bit-shift-right r1 27))
             (unchecked-multiply m2))]
    (bit-xor r2 (unsigned-bit-shift-right r2 31))))

(def h3impl (delay (H3Core/newInstance)))

(defn h3-cell
  "returns H3 index for the cell at the given resolution, 0-15."
  [lat lon res]
  (H3Core/.latLngToCell @h3impl lat lon res))

(def ^:const DEFAULT_H3_RES 5)

(defn xml-node->data
  "extracts info from OSM <node>s"
  [n]
  (let [{:keys [id lat lon]} (:attrs n)]
    {:osm/id (or (parse-long id) (throw (ex-info "barf" {:xml n})))
     :osm/info (into {} (for [t (:content n) :when (= :tag (:tag t))]
                          [(-> t :attrs :k)
                           (-> t :attrs :v)]))
     :lat (parse-double lat)
     :lon (parse-double lon)}))

(declare HASHED?)
(defn location-entity
  "returns an entity map whose (implicit) partition is determined by hashing the
  given lat lon with H3, with medium resolution"
  [lat lon]
  (let [h3 (h3-cell lat lon DEFAULT_H3_RES)
        part (-> h3 mix64 (altmod 512000) d/implicit-part)
        entity {:location/lat+lon [lat lon]
                :location/h3-region h3}]
    (if HASHED?
      (assoc entity :db/id (d/tempid part))
      entity)))

(defn osm-node->tx-data
  "turns OSM info into transaction data"
  [{:keys [osm/id osm/info lat lon]}]
  (let [{:strs [place name]} info]
    (merge (location-entity lat lon)
      (cond-> {:osm/id id}
        place (assoc :osm/place place)
        name (assoc :osm/name name)))))

(def schema
  [{:db/ident :osm/id
    :db/valueType :db.type/long
    :db/doc "OpenStreetMap ID"
    :db/cardinality :db.cardinality/one}
   {:db/ident :osm/name
    :db/valueType :db.type/string
    :db/doc "OSM name tag"
    :db/cardinality :db.cardinality/one}
   {:db/ident :osm/place
    :db/doc "OSM place tag. village, city, etc."
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :location/lat+lon
    :db/valueType :db.type/tuple
    :db/tupleTypes [:db.type/double :db.type/double]
    :db/cardinality :db.cardinality/one}
   {:db/ident :location/h3-region
    :db/valueType :db.type/long
    :db/doc "Uber H3 coord. card-many so that it can be indexed at multiple resolutions"
    :db/cardinality :db.cardinality/many
    :db/index true}])

(defn points-close-to
  "given a h3 region returns other points within this same region"
  [db h3]
  (d/query {:query '{:find [[(pull ?o [:db/id :osm/name :location/lat+lon :location/h3-region]) ...]]}
            :in [$ ?h3]
            :where [[?o :location/h3-region ?h3]]
            :args [db h3]
            :io-context :points/close}))

(defn points-close-to*
  "given a h3 region returns other points within this same region"
  [db h3]
  (d/query {:query '{:find [?o]
                     :in [$ ?h3]
                     :where [[?o :location/h3-region ?h3]]}
            :args [db h3]
            :io-context :points/close}))

(defn process-file
  [file]
  (with-open [rdr (io/reader file)]
    (into []
      (comp (filter #(= (:tag %) :node)) ;; extract only <node ...>
        (map #(-> % xml-node->data osm-node->tx-data)))
      (:content (xml/parse rdr :include-node? #{:element})))))

(defn ingest
  "reads osm datasets, converts them into tx-data, interleaves data between the diferent datasets and transacts"
  [conn osm-files]
  (let [interleaved-data (apply interleave (map process-file osm-files))]
    (run! (fn [batch] (d/transact conn batch)(println "."))
      (partition-all 1000 interleaved-data))))

(comment
  (def path "/home/aldo/Downloads/place-info/")
  (def uri "datomic:dev://localhost:4334/osm")
  (def uri2 "datomic:dev://localhost:4334/osm2")
  (d/create-database uri)
  (d/create-database uri2)
  (d/delete-database "datomic:dev://localhost:4334/osm2")
  (d/release conn)
  (def conn (d/connect uri))
  (def conn2 (d/connect uri2))
  @(d/transact conn schema)
  @(d/transact conn2 schema)

  (def HASHED? true)
  (ingest conn [(str path "mexico.osm.xml") (str path "usa.osm.xml") (str path "brazil.osm.xml")])
  (def HASHED? false)
  (ingest conn2 [(str path "mexico.osm.xml") (str path "usa.osm.xml") (str path "brazil.osm.xml")]))



(def MEXICO_CITY (h3-cell 19.4326 -99.1331785 DEFAULT_H3_RES))
(def NUBANK_OFFICE (h3-cell -23.5607406 -46.6760437, DEFAULT_H3_RES))
(def PASADENA_CA (h3-cell 34.14764, -118.14296, DEFAULT_H3_RES))
(def GHADI (h3-cell 32.76128 -79.99918 DEFAULT_H3_RES))


(def db (d/db conn))
(def db2 (d/db conn2))
(cache/clear (domain/system-cache))
(println (:io-stats (points-close-to db MEXICO_CITY)))
(cache/clear (domain/system-cache))
(println (:io-stats (points-close-to db2 MEXICO_CITY)))