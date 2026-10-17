# WorkManager instantiates workers through their Context/WorkerParameters constructor.
-keep,allowoptimization class dev.colormemo.app.sync.SyncWorker { public <init>(...); }
