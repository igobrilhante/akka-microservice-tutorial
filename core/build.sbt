name := "shopping-cart-service"

val AkkaHttpVersion = "10.2.6"

libraryDependencies ++= Seq(
// 2. Using gRPC and/or protobuf
  "com.typesafe.akka" %% "akka-http2-support" % AkkaHttpVersion
)
