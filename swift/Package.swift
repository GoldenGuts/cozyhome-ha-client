// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "CozyHomeHAClient",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "CozyHomeHAClient", targets: ["CozyHomeHAClient"]),
    ],
    targets: [
        .target(name: "CozyHomeHAClient"),
        .testTarget(name: "CozyHomeHAClientTests", dependencies: ["CozyHomeHAClient"]),
    ]
)
