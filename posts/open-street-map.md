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

Let's define a simple schema, a Location entity. OSM stands for "Open Street Map", we want to save the `id` and the `name` of the data set entries plus the generated h3-region given the latitude and longitud numbers.

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

Then we can create the database

```clojure
(def uri "datomic:dev://localhost:4334/osm")
(d/create-database uri)
(def conn (d/connect uri))
@(d/transact conn schema)
```

Persist the schema
```clojure
@(d/transact conn schema)
```

Now that we have the schema transacted, we can proceed to ingest data from the xml datasets.

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

There are two helper functions, not included in the code block, `mix64`, _bitmixer, take an intermediate hash value that may not be thoroughly mixed and increase its entropy to obtain both better distribution and fewer collisions among hashes_ and `altmod`, _reduction of an integer x into a space [0,max], but faster than (mod x max). You can find the full tutorial code here._

Now we can create an `ingest` function that reads from the xml and transacts data to the database

```clojure
(defn ingest
  "reads osm datasets, converts them into tx-data and transacts"
  [conn osm-file]
  (with-open [rdr (io/reader osm-file)]
    (let [root (xml/parse rdr :include-node? #{:element})]
      (transduce (comp (filter #(= (:tag %) :node)) ;; extract only <node ...>
                       (map #(-> % xml-node->data osm-node->tx-data))
                       (partition-all 1000))
                 (completing
                  (fn [conn batch]
                    (d/transact conn batch)
                    (println ".")
                    conn))
                 conn
                 (:content root)))))
```

### Query data and meassure