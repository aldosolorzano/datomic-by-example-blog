Title: How to use geolocation for partitions in Datomic
Date: 2025-03-19
Tags: datomic, clojure

When fetching data we are looking for it in a physical place, it's better to have these data organized in ways that when asking for data of the same "domain" it's located in the same/or close place. For example in a library when you want to find books about phylosophy, ideally they should be sorted and placed in the phylosophy section. Otherwise you'll have to look also in the novels, science etc... making it more tedious.

Datomic uses partitions to allocate data to a place, acting as a storage hint.

In this tutorial, I'll show you how to use Datomic partitions to group entities based on a geolocation region, I'll use Clojure as programming language and Uber H3 geolocation library, thus queries about MX  will fetch the partition that contains the entities related to this region, that means that further queries about the same region will leverage the partition data being in the cache and avoid the need to fetch more partitions.

## What are Datomic Partitions

Quoting the [documentation glossary](https://docs.datomic.com/glossary.html#partition)
> A logical grouping of entities in a database. Partitions have unique qualified names. Every entity belongs to a partition that is assigned when the entity is created. Partitions act as a *storage hint*, so that larger systems can plan ahead for better locality of reference for entities that are *frequently accessed together*. Partitions are typically coarser grained than relational tables. Partitioning is invisible to the query system, and therefore has *no impact on the code* you write to access the database.

## Code example with Clojure + OpenStreetMap Datasets

```clojure
(defn points-close-to
  "given a h3 region returns other points within this same region"
  [db h3]
  (d/query {:query '{:find [[(pull ?o [:db/id :osm/name :location/lat+lon :location/h3-region]) ...]]
            :in [$ ?h3]
            :where [[?o :location/h3-region ?h3]]}
            :args [db h3]
            :io-context :points/close}))
```

We want to answer the question, *"Given a region what points are close?"* In the query we are fetching all the locations where `:location/h3-region` matches the given h3 region. The query is trivial, the juicy part is what happens behind the scenes to fetch that data. When you pass a region, MX, it would be a good idea to have all the location datoms as close together as possible because it's very likle that you are going to be wanting data about MX, we leverage pulling fewer segments, less memory is consumed and queries are executed faster. We don't want to mix MX data with BR data, otherwise the oposite occurs, more segments are pulled, more memory is consumed and queries are slower because they will have to wait for pulling those other segments. In addition, those other segments hold irrelevant Datoms for the contex which is MX at that moment.

> Less segments containing more Datoms relevant to the context.

Given this example map.

```clojure
{:osm/id ""
 :osm/name "MX"
 :osm/place "Zapopan"
 :location/lat+lon []
 :location/h3-region ""}
```

Let's create 2 databases with different partitioning strategies. One that has good data locality and a second one without any partitioning strategy. We will use the same schema for both to narrow the experiment to the partitioning property.

OSM stands for "Open Street Map", we want to save the `id` and the `name` of the data set entries plus the generated h3-region given the latitude and longitud numbers.

```clojure
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
```

Then we can create the databases.

```clojure
(def uri "datomic:dev://localhost:4334/osm") ;; with partitioning strategy
(def uri2 "datomic:dev://localhost:4334/osm2") ;; NO strategy
(d/create-database uri)
(d/create-database uri2)
(def conn (d/connect uri))
(def conn2 (d/connect uri2))
```

Persist the schema
```clojure
@(d/transact conn schema)
@(d/transact conn2 schema)
```

Now that we have the schema transacted in both databases, we can proceed to ingest data from the xml datasets.

I downloaded the OpenStreetMap dataset and imported all the places in MX/CO/BR/USA with this strategy. You can get them [here](link.to.files)

A simple way is to take a geohash (or Uber H3 location integer) at some chunky resolution (say 200 km^2), then hash it to an integer between 0-512K, and make that the Datomic partition for that entity.

```clojure
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

(defn location-entity
  "returns an entity map whose (implicit) partition is determined by hashing the
  given lat lon with H3, with medium resolution"
  [lat lon]
  (let [h3 (h3-cell lat lon DEFAULT_H3_RES)
        part (-> h3 mix64 (altmod 512000) d/implicit-part)]
    {:db/id (d/tempid part)
     :location/lat+lon [lat lon]
     :location/h3-region h3}))
```

There are two helper functions, not included in the code block, `mix64`, *bitmixer, take an intermediate hash value that may not be thoroughly mixed and increase its entropy to obtain both better distribution and fewer collisions among hashes* and `altmod`, _reduction of an integer x into a space [0,max], but faster than (mod x max). You can find the full tutorial code here._

Now we can create an `ingest` function that reads from the xml and transacts data to the database

```clojure
(defn process-file
  "reads file with-open and returns a vector with the xml/parse result"
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
```

and process the files and transact data

```clojure
(def path "/home/aldo/Downloads/place-info/") ;; Should be the path where you downloaded the files
(def HASHED? true)
(ingest conn [(str path "mexico.osm.xml") (str path "usa.osm.xml") (str path "brazil.osm.xml")])
(def HASHED? false)
(ingest conn2 [(str path "mexico.osm.xml") (str path "usa.osm.xml") (str path "brazil.osm.xml")])
```

### Query data and meassure

Compare against both partitioning strategies.

> Hypothesis: fewer :dev segments pulled and :dev-ms timing should be a considerable difference.

Running the query more than once will get everything from cache. To make the difference most pronounced is to clear ocache prior to running a single query.

Clear cache, then run this a single time `(:io-stats (points-close-to db MEXICO_CITY))`.

```clojure
(def MEXICO_CITY (h3-cell 19.4326 -99.1331785 DEFAULT_H3_RES))
(def db (d/db conn))
(def db2 (d/db conn2))

(cache/clear (domain/system-cache))
(println (:io-stats (points-close-to db MEXICO_CITY)))

(cache/clear (domain/system-cache))
(println (:io-stats (points-close-to db2 MEXICO_CITY)))
```

```clojure
{:io-context :points/close,
 :api :query,
 :api-ms 41.63,
 :reads {:dir 2985,
         :aevt 2984,
         :avet 2,
         :aevt-load 3,
         :deserialize-ms 3.83,
         :deserialize 5,
         :dev-ms 2.81,
         :avet-load 2,
         :dev 5,
         :ocache 5}}

{:io-context :points/close,
 :api :query, :api-ms 89.27,
 :reads {:dir 2985,
         :aevt 3169,
         :avet 2,
         :aevt-load 47,
         :deserialize-ms 40.28,
         :deserialize 49,
         :dev-ms 18.42,
         :avet-load 2,
         :dev 49,
         :ocache 233}}
```

Focus on the following attributes `:api-ms` total roundtrip time,  `:aevt-load`, segments distribution, and `:dev` numbers of segments pulled from the storage layer.

Comparing db vs db2,
- `:api-ms`, it's 41.63 in `db` roughly the double in `db2` 89.27
- `:dev`, it's 5 in db and 49 in db2
- `:aevt-load`, the difference is huge, 2 in `db` and 47 in `db2`. Means more segments, spread around

### Amplify the effect
To make the impact more evident let's ingest the same data twice, that way the database will be bigger.

```clojure
(def path "/home/aldo/Downloads/place-info/") ;; Should be the path where you downloaded the files
(def HASHED? true)
(ingest conn [(str path "mexico.osm.xml") (str path "usa.osm.xml") (str path "brazil.osm.xml")])
(def HASHED? false)
(ingest conn2 [(str path "mexico.osm.xml") (str path "usa.osm.xml") (str path "brazil.osm.xml")])
```

then we run the queries again

```clojure
(def MEXICO_CITY (h3-cell 19.4326 -99.1331785 DEFAULT_H3_RES))
(def db (d/db conn))
(def db2 (d/db conn2))

(cache/clear (domain/system-cache))
(println (:io-stats (points-close-to db MEXICO_CITY)))

(cache/clear (domain/system-cache))
(println (:io-stats (points-close-to db2 MEXICO_CITY)))
```

```clojure
{:io-context :points/close,
 :api :query,
 :api-ms 75.41,
 :reads {:dir 11937,
         :aevt 11936,
         :avet 2,
         :aevt-load 6,
         :deserialize-ms 7.0,
         :deserialize 8,
         :dev-ms 3.99,
         :avet-load 2,
         :dev 8,
         :ocache 8}}

{:io-context :points/close,
 :api :query,
 :api-ms 217.33,
 :reads {:dir 11937,
         :aevt 12943,
         :avet 2,
         :aevt-load 120,
         :deserialize-ms 96.67,
         :deserialize 122,
         :dev-ms 46.69,
         :avet-load 2,
         :dev 122,
         :ocache 1126}}
```

Comparing db vs db2,
- `:api-ms`, it's 75.41 in `db` almost triple in `db2` 217.33
- `:dev`, it's 8 in db and 122 in db2
- `:aevt-load`, the difference is even higher, 6 in `db` and 122 in `db2`. Means more segments, spread around

## Conclusion

There is no `right` way to partition data, it is context dependent and thinking about it impacts in the performance of the system, either positive or negative.