(defproject hiwian "0.3.5"
  :description "A fork of hiwian, to get back old functionality"
  :url "http://github.com/conaws/hiwian/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.170"]
                 [org.clojure/core.async "0.2.374"]
                 [reagent "0.5.1"]
                 [datascript "0.13.3"]
                 ;[figwheel-sidecar "0.5.0-SNAPSHOT" :scope "test"]
                 ]
  :plugins [[lein-cljsbuild "1.1.3"]]
  :cljsbuild {
              :builds [ {:id "hiwian" 
                         :source-paths ["src/"]
                         :figwheel false
                         :compiler {:main "hiwian.core"
                                    :asset-path "js"
                                    :output-to "resources/public/js/main.js"
                                    :output-dir "resources/public/js"} } ]
              }
  :scm {:name "git"
        :url "https://github.com/Conaws/hiwian"})
