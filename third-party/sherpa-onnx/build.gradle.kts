// Envuelve el AAR oficial de sherpa-onnx (Apache 2.0, v1.13.8) para que otros módulos lo usen.
configurations.maybeCreate("default")
artifacts.add("default", file("sherpa-onnx-1.13.8.aar"))
