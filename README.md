# tako

A Clojure library for efficient data access using batching and caching.

tako is largely inspired by [graphql/dataloader](https://github.com/graphql/dataloader) 
and is designed to optimise fetching data from remote sources (DBs, APIs) by batching 
requests across independent parts of your system.

There are some other interesting libraries in this space such as the 
[haxl](https://github.com/facebook/Haxl) -inspired [muse](https://github.com/kachayev/muse)
and [urania](https://github.com/funcool/urania). A key difference is that while these
latter libraries build an AST and optimise the fetching and computation of a complex
query from a common root, dataloader (and tako) optimise fetching via time-based
batching across independent sources (i.e. not a common root).

## Status

tako is under active development and may experience breaking changes. 
Please try it out and raise any issues you may find.

## Usage

For tools.deps, add this to your deps.edn:

```clojure
{tako/tako {:mvn/version "0.1.0-alpha1"}}
```

For Leinengen, add this to your project.clj:

```clojure
[tako "0.1.0-alpha1"]
```

## Getting started

The primary feature of tako is batching independent requests into one. Therefore,
we start by defining a `batch-fn` that, given a list of keys, returns a list of
results correpsnding to those keys.

```clojure
(def batch-fn (fn [ks] (map #(str "hello, " %) ks)))
```

Creating a loader with this batch-fn then allows us to fetch both single and 
multiple keys.

```clojure
;; Start a loader with your batch function.
(def loader (t/start! batch-fn))

;; Use it to load many keys
(deref (t/load-many loader ["alice" "bob"]))

;; Use it to load a single key
(deref (t/load-one loader "clair"))

;; Stop it when you are finished
(t/stop! loader)
```

## GraphQL Usage

Lacinia allows injecting attributes into the execution context. 

Given different users will have unique access rights to resources, we recommend that 
a new loader is created per request to avoid returning cached data that should not be visible to a user.
To avoid the loader doing anything until necessary, you can wrap it with `delay`.

The body of your handler might look like:

```clojure
;; -- In your http handler --

(defn create-loaders []
  {:load-greetings (delay (t/start! (fn [ks] (map #(str "hello, " %) ks))))})

;; Add loaders to the graohql execution context
(let [loaders (create-loaders)
      result (g/execute schema query variables {:loaders loaders})]
  
  ;; Clean up before we return the result
  (doseq [ld loaders]
    (t/stop! @ld))
  result)

;; -- In some async resolver --

(defn get-person-greeting [ctx _ person]
  (let [ld (-> ctx :loaders :load-greetings)]
    (deref (t/load-one @ld (:id person)))))
```

With the above, if we fetch greetings for two or more people anywhere in our GraphQL query,
all the calls to `load-one` will be collected and dispatched once.

## Usage with pedestal

[Pedestal](https://github.com/pedestal/pedestal) is based on interceptors, so we can add the loaders
and clean them up before they get to the terminal handler.

Below is an example combining Pedestal with 
[lacinia-pedestal](https://github.com/walmartlabs/lacinia-pedestal) that provides some 
 building blocks for convenience (e.g. the graphiql IDE).

```clojure
; Create an interceptor that adds loaders to the request 
; and cleans them up after response is generated.

(def attach-loaders 
  (let [cleanup (fn [context]
                  (when-let [loaders (get-in context [:request :loaders])]
                    (doseq [[k ld] @loaders]
                      (println "closing loader: " k)
                      (.close @ld)))
                  context)]
  {:name ::attach-loaders 
   :enter (fn [context] (assoc-in context [:request :loaders] (atom (create-loaders))))
   :leave cleanup
   :error cleanup}))


; Handle the graphql request, passing the loaders into the graphql context

(def graphql-handler [req]
  (let [{:keys [query variables]} (:json-params req)
        result (g/execute graphql/schema query variables (select-keys req [:loaders]))]
  {:status 200 
   :body result 
   :headers {}}))


; Basic routing for graphql API and IDE, transforming json request and response.

(def routes 
  (let [asset-path "/assets/graphiql"]
    (into #{["/graphql" :post [(io.pedestal.http.body-params/body-params)
                               io.pedestal.http/json-body 
                               attach-loaders 
                               `graphql-handler]]
            ["/graphiql" :get (lp/graphiql-ide-handler {:asset-path asset-path
                                                        :api-path   "/graphql"}) :route-name :graphiql]}
          (lp/graphiql-asset-routes asset-path))))
```