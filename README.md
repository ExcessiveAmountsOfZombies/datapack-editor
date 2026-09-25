# EpheroLib

To Depend on this library: 

For all projects you will need to access the repo

```
maven {
    name "Epherical Maven"
    url "https://maven.epherical.com/releases"
}
```

In the root project:

```
dependencies {
    compileOnlyApi('com.epherical.epherolib:EpheroLib:1.0.0-1.20.1')
}
```
In Fabric:
```
dependencies {
    modImplementation('com.epherical.epherolib:EpheroLib-fabric:1.0.0-1.20.1')
}
```
In Forge:
```
dependencies {
    implementation (fg.deobf('com.epherical.epherolib:EpheroLib-forge:1.0.0-1.20.1'))
}
```

## Editor model preview

The editor loads [block-model-renderer](https://github.com/ewanhowell5195/block-model-renderer) version 2 from [jsDelivr](https://cdn.jsdelivr.net/npm/block-model-renderer@2/dist/block-model-renderer.min.js) at runtime. Copyright © Ewan Howell. The library is licensed under [MPL-2.0](https://github.com/ewanhowell5195/block-model-renderer/blob/master/LICENSE). Its JavaScript and bundled renderer assets are fetched from the CDN; they are not included in EpheroLib's distribution.

The editor serves model JSON and texture files on demand from loaded resource packs and jars in the `mods` folder. The browser needs access to jsDelivr for model previews.
