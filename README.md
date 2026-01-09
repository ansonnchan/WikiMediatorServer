# WikiMediator Project

**Course:** CPEN 221 – Software Construction I, University of British Columbia  
**Author:** Anson Chan  

---

## Overview

The **WikiMediator Project** is a Java-based server application developed as part of UBC's CPEN 221 course. Its purpose is to **mediate requests to a simplified Wikipedia-like system**, handling multiple client requests **concurrently** while maintaining **thread safety**.  

The project demonstrates key software construction concepts, including:

- Concurrency and synchronization  
- Safe buffer management  
- Modular and testable design  
- Automated testing with **over 60+ JUnit tests**

The server ensures that shared resources are accessed safely in a multi-threaded environment, preventing race conditions and ensuring consistent data handling.

---

## Features

- **Multi-threaded request handling:** Supports multiple clients simultaneously.  
- **Thread-safe buffer management:** Guarantees correct concurrent access to shared resources.  
- **Automated testing:** Over 60 JUnit tests covering server logic, buffer operations, and concurrency scenarios.  
- **Robust error handling and logging** for debugging and reliability.  
- **Extensible architecture** suitable for further enhancements.

---

## Prerequisites

- Java 17 or higher  
- Gradle 8.x or higher  
- Git (to clone the repository)

---

## Check for Gradle
Before building the project, make sure Gradle is installed. You can check by running:

```bash
gradle --version
```

- If you see the Gradle version information, you are ready to build the project.

- If not, you can download and install Gradle from https://gradle.org/install/ where you can download for Windows, Mac, and Linux

**Alternatively**, the project includes a **Gradle wrapper** so you can run Gradle commands without installing Gradle globally.
```bash
./gradlew build
./gradlew run
```

For Windows, run these commands
```bash
./gradlew.bat build
./gradlew.bat run
```

## Setup and Run

1. Clone the repository:
   ```bash
   git clone https://github.com/ansonnchan/WikiMediatorServer.git
   cd WikiMediatorServer

2. Build the project with Gradle:
   ```bash
   ./gradlew build

3. Run the server:
   ```bash
   ./gradlew run

4. Run all tests:
   ```bash
   ./gradlew test


## License and Copyright

This project is developed as part of the CPEN 221 course at the University of British Columbia. All code and materials are the intellectual property of UBC and the course instructors unless otherwise stated.

You may view, study, and modify the code for educational purposes within the course context only. Redistribution or commercial use is not permitted without explicit permission.

