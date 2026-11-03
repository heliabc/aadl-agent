# OpenSUT: original architecture and requirements excerpts

Source: https://github.com/GaloisInc/VERSE-OpenSUT/tree/2b441e6e6ff00b176a46d4d2577d19aca9f2f4e4

The following passages are copied from the original README and StrictDoc requirement sections. Requirement IDs and rationale are retained; no design details are inferred from the AADL reference model.

Source: `README.md`, ## Introduction

## Introduction

Open System Under Test (OpenSUT) is a fictitious airborne unmanned platform that represents a notional high-consequence national security system. OpenSUT has a basic autopilot, and in our default configuration it is a fixed wing platform. OpenSUT was developed to provide an open-source system for testing and evaluation of VERSE tools, and provided very valuable feedback to the VERSE TA1 team.

The VERSE hypothesis is that a strong assurance toolchain will increase the assurance of low-assurance components, mitigate risk in third-party components, and maintain the high level of assurance in high-assurance components with lower time, effort, and expertise.

**IMPORTANT!** Please note that OpenSUT is merely a representative example of existing systems, and as a result it does *not* necessarily follow the best design and security practices as known today. Galois has previously demonstrated how to build systems that are *correct-by-construction* and inherently secure - this was demonstrated for example with the [SMACCMPILOT](https://smaccmpilot.org/) quadcopter on the [DARPA HACMS](https://www.galois.com/project/hacms) project. OpenSUT on the other hand represents a system that is *not* secure by design, and can showcase how TA1 tools help secure real defense systems.

OpenSUT [components](#components) can be thought of as the *application code*, and each component runs inside a [virtual machine](#virtual-machine). The components run on a one or more of host computers, with either multi-core CPUs running multiple components, or single core CPUs hosting a Real-Time-Operating-System (RTOS) and running a single component (typically the [autopilot](#autopilot)). Running the components in such a virtualized environment, including virtualized hardware, mirrors the architecture of real platforms. For demonstration purposes, we run all components in a single (emulated) host computer (represented by a docker image, see [OPENSUT_DOCKER.md](./docs/OPENSUT_DOCKER.md) for more details).

OpenSUT contains a [Mission Protection System](#mission-protection-system-mps) (MPS) which protects the (virtual) engine from getting outside of its safe operating conditions, a [Mission Key Management](#mission-key-management-mkm) system (MKM) that handles [mission keys](./models/DOMAIN_MODEL.md/#mission-keys), platform [attestation](./models/DOMAIN_MODEL.md/#attestation) and provides various cryptographic services provided by the [Platform Crypto](#platform-crypto), a [system logger](#system-log) for logging system events at different classification levels, and an [autopilot](#autopilot) provides basic flight control and waypoint following ability. The components communicate via point-to-point socket connections. Each component is started via [secure boot](#secure-boot) to ensure that only known components can be run.

OpenSUT uses a standard linux with [pKVM](#pkvm) support as a [hypervisor](#hypervisor), and while we realize that a standard linux kernel is not real-time capable and is not a certifiable for airworthiness, it is a good open substitute for [Lynx Secure](https://www.lynx.com/products/lynxsecure-separation-kernel-hypervisor) that we intend to use for the proprietary SUT in Phase 2. All CPUs are ARM64 architecture, because pKVM supports only that instruction set. For easy deployment, we will emulate the host computers in QEMU instances. Some auxiliary processes, such as a flight simulator, are expected to run directly on the user's machine, or in separate docker containers.

Below is an overview of OpenSUT. The (emulated) host computer is yellow, each pKVM virtual machine is light yellow, the secure boot is blue, and each component (application code) is purple.

![OpenSUT overview](./docs/figures/OpenSUT_overview.png)

Source: `README.md`, ## Scenarios

## Scenarios

OpenSUT shall operate in the following scenarios:

### Scenario 1: Boot OpenSUT to a known initial state

In this scenario, one or more components of OpenSUT boot using [SHAVE Trusted Boot](./components/platform_crypto/shave_trusted_boot/). It means that the application code is measured, hashed, and compared against an expected measure. Only if these values match, the application code is started and the measure is stored in the memory. If they don't match, an error is thrown, the boot is aborted and an error message is possibly sent and logged. If the attestation of each securely booted component passes, the system will be in a known initial state, *fully provisioned*. Measured boot ensures that only the expected code is running on OpenSUT.

The code is measured either with SHA256 or with [quantum safe](https://www.ibm.com/topics/quantum-safe-cryptography) [eXtended Merkle Signature Scheme](https://datatracker.ietf.org/doc/html/rfc8391) (XMSS).

For the purpose of this scenario, we assume that each host computer contains a [root of trust](#root-of-trust), a [trusted boot](#trusted-boot) that can bring up the [hypervisor](#hypervisor). In other words, we assume the host OS to be *trusted* (see the [Threat model](#threat-model)). Because hardware root of trust, trusted boot and attestation are all complex topics, only the *application code* will be attested in this scenario.

### Scenario 2: Load mission key

Once the platform is *fully provisioned*, components can request the [mission keys](#mission-keys) from the [Mission Key Management](#mission-key-management-mkm) (MKM) component. In this scenario the keys are loaded into MKM during the build process. In real systems the loading could happen through a direct UART connection to the MKM, or through the [message bus](#message-bus). In both cases, we expect the keys to be encrypted, such that they are never transported in plaintext.

The platform data have two different classification levels (*low* and *high*), the *low* data are unencrypted, while the *high* data are protected by the mission keys. The keys are used for the encryption of data both *in transit* (data sent between components) and *at rest* (e.g., stored in [System Log](#optional-system-log)). We expect the *high* key to be used for encrypting *high* data any time they are exchanged between components, and the *low* key is used to encrypt data logs. 

Each component requesting the keys from the MKM must first [attest](#attestation) its state to the MKM. The attestation protocol is described [here](./components/mission_key_management/README.md/). In this scenario, the logging component requests a *low* key to encrypt data-at-rest.

We intend to use the standard symmetric keys (e.g. AES256), and optionally an asymmetric key for a [pot-quantum cryptographic](https://en.wikipedia.org/wiki/Post-quantum_cryptography) algorithm (e.g., [KYBER](https://en.wikipedia.org/wiki/Kyber) or Dilithium). We assume there is no adversary on the bus, thus the messaging bus can be trusted. Because the bus is redundant, the messages are assumed to be always delivered correctly. In such case, mission keys can be exchanged directly between components.


### Scenario 3: Execute a mission

After the OpenSUT boots up, initializes to a known state, and loads mission keys, a mission plan is uploaded. The OpenSUT's autopilot then takes off, flies the mission following a set of waypoints, returns to land, and lands at the same position as it started from.

![autopilot mission](./docs/figures/opensut-autopilot.png)

### Scenario 4: Decommission the OpenSUT

When a mission is completed, or when the OpenSUT is about to be shut down, ensure all data is properly saved to the [System Log](#system-log). The system logs are then exported, and the keys are erased from the [Mission Key Management](#mission-key-management-mkm) component. Erase all sensitive data from the OpenSUT. Practically for OpenSUT this means shutting down the VMs and saving log data to an encrypted disk that can be inspected after the mission.

![OpenSUT scenarios](./docs/figures/OpenSUT_scenarios.png)

Source: `README.md`, ### Message Bus

### Message Bus

P2P connection between endpoints provided by a SW layer. Link layer is handled by a fictitious redundant bus, ensuring packet delivery. Needs to support both *low* and *high* data. We originally planned to use [ZeroMQ](https://zeromq.org/)'s [C implementation](https://zeromq.org/languages/c/), but instead we use a simple socket connection between components, utilizing standard Linux network stack. This configuration allows us to express all relevant properties and provides needed functionality, without the need of additional 3rd party library.


### Mission Key Management (MKM)

* **Description:**
  * Distributes mission keys to components that can attest their configuration (see the [README](./components/mission_key_management/README.md) for details)
* **Primary language:** C
* **Source:**
  * the component is written from scratch

Source: `README.md`, ### System Log

### System Log

* **Description:**
  * A system logger
  * Concurrent & distributed, able to log at different classification levels (*low* and *high*).
  * Note that the JML specifications need to be translated to CN, and the Java code to be ported to C (at least a minimal subset)
* **Primary language:** Java (will be translated into C)
* **Source:**
  * [FreeAndFair Logging](https://github.com/FreeAndFair/logging/)

[Domain Engineering]: https://en.wikipedia.org/wiki/Domain_engineering
[OMS]: https://www.vdl.afrl.af.mil/programs/oam/oms.php

## Mission Key Management Requirements

Source: `REQUIREMENTS.sdoc`, Mission Key Management Requirements

Mission Key Management (MKM) processes key requests and distributes keys to other components. Any component can connect to the MKM, request a key, and attest to the code that it's running; the MKM will then send the key if allowed by the MKM's built-in policy.

We require the MKM to implement the following protocol:

1. The client connects to the MKM over TCP.
2. The client component sends a key ID (1 byte), indicating which key it is
   requesting.
3. The MKM sends a random nonce (16 bytes).
4. The client obtains an attestation matching the challenge (by communicating
   with its trusted boot daemon) and sends the attestation (64 bytes).
5. If the attestation is valid and MKM policy allows the component to receive
   the requested key, the MKM sends the key (32 bytes).

If an error occurs, such as an invalid attestation or a policy violation, the
MKM simply closes the connection without sending the key.

Since all messages have a fixed size and occur in a fixed order, the protocol
does not use any headers or delimiters for messages.

The MKM server listens on localhost (127.0.0.1) port 6000 by default.  To
change this, set the `MKM_BIND_ADDR` and/or `MKM_PORT` environment variables.
For example, `MKM_BIND_ADDR=0.0.0.0 MKM_PORT=6001 ./mkm config.bin` will cause
it to listen on port 6001 on all network interfaces.

### TA2-REQ-66 — Close connection on error

If an error occurs at any time during the key exchange protocol, such as an invalid attestation or a policy violation, the MKM shall close the connection without sending the key.

### TA2-REQ-67 — No headers or delimiters for messages

All MKM messages shall have a fixed size and occur in a fixed order, and the protocol shall not use any headers or delimiters for messages.

### TA2-REQ-68 — TCP connection

The client shall connect to the MKM over TCP via a socket.

### TA2-REQ-69 — Wait for key ID

While the MKM is ready to receive connections, a client component shall send a key ID (1 byte), indicating which key it is requesting.

### TA2-REQ-70 — Send challenge

When a key ID is received from a client, the MKM shall send a random nonce (16 bytes) in return.

### TA2-REQ-71 — Valid key ID

The MKM shall process only a valid key ID.

### TA2-REQ-72 — Calculate attestation

Once the client receives an attestation challenge (nonce) from the MKM, the client shall compute the response by communicating with its trusted boot daemon and send the response back to the MKM.

### TA2-REQ-73 — Challenge response format

The challenge response shall be computed by concatenating the current measured value (matching the expected hash of the binary) with the received nonce, and then computing HMAC of the concatenated value using a secret key. The resulting response is 64 bytes long.

### TA2-REQ-74 — Secure boot secret key

The secret key may be identical across different components, so as to simplify the key management. This key is known at build time to the MKM.

Rationale:

In real world, secure boot would store unique and shared keys in a Hardware Root of Trust (HROT) and the decision whether to use unique or shared keys would be based on the actual threat model. In either way, the MKM must know the key to validate the attestation response.

### TA2-REQ-75 — Receive response

Once the MKM receives the attestation response, it shall check its validity. A valid attestation is calculated as described in TA2-REQ-73.

### TA2-REQ-76 — Send key

If the received response is valid, the MKM shall send back to the client the associated mission key and terminate the connection.

### TA2-REQ-77 — Key format

The mission key is 32-bytes long symmetric AES key.

## Logging Component Requirements

Source: `REQUIREMENTS.sdoc`, Logging Component Requirements

### TA2-REQ-78 — Save telemetry to disk

The logging component shall connect to the secondary autopilot telemetry port and record some or all telemetry values to disk.

### TA2-REQ-79 — Read from a socket

The logging component shall read MAVlink messages from a socket

### TA2-REQ-80 — Log file format

Logs shall be saved in text format, with a timestamp on each line.

### TA2-REQ-81 — Debug print

Logs may be printed to stdout for debugging purposes.

### TA2-REQ-82 — Encrypted storage

Logs shall be encrypted by storing them on an encrypted filesystem.

### TA2-REQ-83 — Encryption keys

The key for the encrypted filesystem shall be obtained from the Mission Key Management component.

### TA2-REQ-84 — Filesystem initialization

The filesystem shall be initialized on first use.
