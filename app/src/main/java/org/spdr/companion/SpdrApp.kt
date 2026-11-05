package org.spdr.companion

import android.app.Application

/** Process-wide singletons: the engine outlives activities, the service drives its sensors. */
class SpdrApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var engine: Engine
        private set
    lateinit var store: GraphStore
        private set
    lateinit var bridge: OsmAndBridge
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        engine = Engine(this, prefs)
        store = GraphStore(this)
        bridge = OsmAndBridge(this, engine)
        LinkBridge(this).listen()
        Thread {
            store.loadCached()?.let { engine.setGraph(it) }
        }.start()
    }
}
