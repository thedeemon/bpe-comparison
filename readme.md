# Byte Pair Encoding in different languages

I was toying with implementing BPE in Swift, then got curious how fast it is compared
to other languages, and made a few translations. Most of them were translated mechanically
using AI assistants, then edited and optimised a bit manually.

The program spends almost all its time walking and mutating an array of UInt16 
(or whatever substitute when UInt16 isn't available) and updating some hash tables.
So the main differences are in hash table implementations, and also in how nullable / optional
types are represented and handled.

Compared on a 1000000 byte file (enw6):

| Language      | Time, s    | Max Memory, MB |
| ------------- | ---------- | -------------- | 
| Swift         | 57         | 33             |
| D             | 132        | 55             |
| Kotlin        | 205        | 1070           |
| C++           | 270        | 27             |
| Scala         | 947        | 977            |

(there's also a multithreaded version in Swift that ran in 30 s)

How the programs were built:

````
// Swift version 5.10.1 (swift-5.10.1-RELEASE)
// Target: x86_64-unknown-linux-gnu
> swiftc -O bpe_st.swift -o bpe_st

// LDC - the LLVM D compiler (1.39.0-sym1):
//  based on DMD v2.109.0 and LLVM 18.1.6
> ldc2 -O2 --release bpe.d -ofbped

// kotlinc-jvm 2.0.0 (JRE 22.0.1+8)
> kotlinc bpe.kt -include-runtime -d bpekt.jar
 
// g++ (GCC) 14.1.1 20240522
// also tried clang, it's not faster
> g++ -std=c++17 -O2 -o bpecpp bpe.cpp

// Scala 3.2.2, JVM
> scala-cli package bpe.scala -o bpe-scala.jar

// Running Kotlin and Scala programs:
// OpenJDK 64-Bit Server VM (build 22.0.1+8, mixed mode, sharing)
> java -jar program.jar enw6

// OS: Manjaro Linux x86_64
// Kernel: 6.6.40-1-MANJARO
// CPU: Intel i7-10710U (12) 
````

