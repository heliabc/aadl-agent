# Autonomous Passenger Ship Communication Architecture — Derived Example Requirements

> Provenance: model-derived example, curated with AI assistance and checked against the pinned source.
> This is not an original stakeholder specification. Use it for architecture reconstruction and tool demonstrations; do not use it for independent evaluation or as a gold answer.

Model the following selected architecture in AADL. Requirements identified below are normative for this example; scope limitations are explanatory and do not request reproduction of source defects.

## Scope

All ten ecosystem participants and their active communication paths

APS and RCC decomposition, redundant communication network, sensor aggregation and actuator interfaces

Author-stated embedded functional/security properties and emergency-button requirement C-X-2

## Requirements

### APS-REQ-001

The architecture shall represent an autonomous passenger ship (APS), a remote control center (RCC), an emergency control team (ECT), a shore sensor system (SSS), other ships, traffic information services (TIS), stakeholders, a cloud service, a mobile 5G network, and an aids-to-navigation service as separate system-level participants.

### APS-REQ-002

The APS shall support voyage management, condition detection and analysis, safe-speed operation, maneuvering, docking, alert management, and location advertisement.

### APS-REQ-003

The RCC shall support remote monitoring, remote operation, condition analysis, and autonomous ship controller (ASC) updates. The ECT shall support emergency intervention and emergency control of the APS.

### APS-REQ-004

The shore participant shall support docking, charging, and passenger embarkation and disembarkation; TIS shall support traffic management; the other-ship participant shall support collision avoidance; and the stakeholder participant shall provide stakeholder communication.

### APS-REQ-005

The APS boundary shall expose bidirectional data interfaces for Internet access, AIS traffic, direct RCC communication, and emergency radio communication; input data interfaces for UHF position correction and GNSS signals; and an output event interface for mobile emergency notification.

### APS-REQ-006

The ecosystem shall provide a direct bidirectional APS-to-RCC link, a separate APS-to-RCC AIS link, bidirectional APS and RCC links to the mobile 5G network, and bidirectional AIS links connecting both APS and RCC to other ships and TIS.

### APS-REQ-007

Navigation, safety, machinery, and power information from the APS shall have declared flows to the RCC over the direct link and to the cloud over the mobile network. The RCC shall also have declared receive flows for these four information classes through the mobile-network and cloud routes.

### APS-REQ-008

At the ecosystem system boundaries, declare emergency radio flows for navigation, safety, machinery and power information from APS to ECT and for emergency commands from ECT to APS. Declare a separate one-way mobile-event path for the APS emergency signal to ECT.

Qualification: These are boundary flow declarations and author-stated receive intent. ECT.aadl connects its internal emergency module's wireless port outward to RadioCommunication, but does not establish the corresponding inbound path from the ECT boundary to that module. End-to-end reception is not claimed.

### APS-REQ-009

The cloud service shall authenticate and associate APS and RCC participants, facilitate stakeholder communication, and support online data backup. The mobile network shall provide quality-of-service support.

### APS-REQ-010

The cloud shall have a bidirectional Internet connection to stakeholders and to the mobile network; the shore sensor system shall connect to that mobile network. Stakeholders shall be able to request cloud access and receive management information from the cloud.

### APS-REQ-011

The APS traffic module shall use a bidirectional VHF data interface for AIS traffic, receive GNSS input, output local navigation information, and accept management input. It shall support wireless traffic management and remote management.

### APS-REQ-012

The aids-to-navigation service shall provide a UHF correction signal to the APS navigation system. The navigation system shall distribute incoming GNSS signals to two GNSS receivers and distribute the RTK-UHF correction output to both main and backup GNSS/IMU units.

### APS-REQ-013

The APS shall contain an Internet module, a direct RCC module, a traffic module, an emergency control module, main and backup core switches, primary and secondary WAN load balancers, an ASC, machinery and navigation subsystems, a safety subsystem, a battery-management subsystem, an emergency-button transmitter, and two intrusion-detection subsystems.

### APS-REQ-014

Each of the two WAN load balancers shall connect to both the Internet module and the direct RCC module through separate Ethernet ports. Both core switches shall connect to both load balancers; the core switches shall have a mutual redundancy link, and the load balancers shall have their own mutual redundancy link.

### APS-REQ-015

Each WAN load balancer shall support VPN communication, quality of service, load balancing, policy-based routing, redundant LAN access, failover, backup, and remote management, with secure communication and user-access management.

### APS-REQ-016

Both main and backup core switches shall support VLANs, inter-VLAN routing, packet switching, failover, backup, and remote management, provide at least twelve Ethernet interfaces, and support secure communication and user-access management.

### APS-REQ-017

The Internet module shall support 5G wireless Internet access and IP routing, expose two LAN data ports and a management port, support redundant LAN access and backup, transmit ship information, receive control commands, carry ASC updates, and support secure communication and remote user-access management.

### APS-REQ-018

The direct RCC module shall support wireless point-to-point IP communication with two LAN data ports and a control port; it shall support redundant LAN access, backup, ship-data transmission, control-command reception, ASC updates, and secure remote management with user-access control.

### APS-REQ-019

The ASC shall comprise main and backup controller subsystems. Each shall contain collision-avoidance/situational-awareness processing, network-and-system management, and an information-security management subsystem (IS3MS). Each function shall expose primary and secondary network interfaces; the backup controller shall additionally expose an emergency-control interface.

### APS-REQ-020

Main ASC collision-avoidance, network-management, and information-security interfaces shall connect to the primary and secondary WAN load balancers. Corresponding backup ASC interfaces shall connect to the main and backup core switches. The ASC emergency interface shall connect to the emergency control module.

### APS-REQ-021

The navigation subsystem shall contain five radar devices, four lidar devices, five electro-optical devices, one dome camera, and one infrared camera. It shall contain one top sensor-processing unit, four hull sensor-processing units, two sensor switches, two GNSS receivers, main and backup GNSS/IMU units, and one RTK-UHF unit.

### APS-REQ-022

The top sensor-processing unit shall aggregate one radar, one infrared camera, one dome camera, and one electro-optical device. Each of the four hull sensor-processing units shall aggregate one electro-optical device, one lidar, and one radar. Each processing unit shall have an output connection to each of the two sensor switches.

### APS-REQ-023

Sensor-processing units shall provide secure sensor and LAN access, transmit processed sensor information, and support at least four sensor interfaces. Each sensor switch shall support managed packet switching, secure access, redundant LAN access, and at least eight Ethernet interfaces.

### APS-REQ-024

Both GNSS/IMU units shall accept GNSS receiver data and RTK-UHF correction data, provide two LAN data interfaces, and support secure access and redundant LAN communication.

### APS-REQ-025

The navigation subsystem shall expose four primary and four backup data interfaces for its two sensor-switch paths and two GNSS/IMU paths. The APS shall attach the primary interfaces to the main core switch and the backup interfaces to the backup core switch.

### APS-REQ-026

The machinery subsystem shall provide four azimuth thrusters controlled by two I/O cards, with each card driving two thruster signal inputs. Each I/O card shall have primary and backup LAN access and shall support secure access and thruster interfacing.

### APS-REQ-027

The safety subsystem shall contain a user panel and a general I/O module controlling an anchor-drop device, a horn, an alarm, and a lantern. The panel shall feed the I/O module, whose four output signals shall drive these four devices respectively; the module shall provide main and backup LAN access.

### APS-REQ-028

The battery-management subsystem shall expose main and backup bidirectional data ports, with a power-information source flow on each, and connect those ports to the main and backup core switches respectively.

### APS-REQ-029

The APS emergency control module shall provide secure wireless communication, transmit emergency navigation and machinery information, and receive emergency control commands. The ECT counterpart shall receive that information and transmit emergency control commands over a secure wireless interface.

Qualification: These are boundary flow declarations and author-stated receive intent. ECT.aadl connects its internal emergency module's wireless port outward to RadioCommunication, but does not establish the corresponding inbound path from the ECT boundary to that module. End-to-end reception is not claimed.

### APS-REQ-030

The emergency push button shall be conveniently located and operated. It shall trigger an always-on, outgoing-only mobile communication module; the ECT shall receive the resulting signal through an always-on, incoming-only mobile receiver with alarm interfacing.

### APS-REQ-031

The RCC shall contain network-and-system management, IS3MS, intrusion detection, an operator joystick, Internet, direct APS and traffic modules, a main control computer, and primary and secondary WAN load balancers. Both Internet and direct APS modules shall connect to both load balancers, and the main control computer shall connect to both load balancers.

### APS-REQ-032

The RCC main control computer shall receive navigation, power, safety, machinery, shore-sensor, and other ship information; transmit control commands; and transmit ASC updates. The RCC direct APS module shall receive ship information and transmit control commands and ASC updates.

### APS-REQ-033

Collision-avoidance/situational-awareness processing in both ASC branches shall support user-access management, anti-virus protection, and firewall protection.

### APS-REQ-034

Two APS intrusion-detection subsystems shall be retained at distinct network attachment points: one connected to both WAN load balancers, and the other connected to both core switches.

### APS-REQ-035

The general I/O module shall interface non-IP safety devices while providing redundant LAN access and secure access; the local safety user panel shall also support secure access.

## Scope limitations

- Selected scope is Overall::APSCommunicationArchitecture.impl and its selected source components, not every alternative implementation among the 43 A3D instances.

- The source has mixed abstraction levels, commented connections, empty implementations and inconsistent flow directions. Duplicate connections and suspected direction errors are not elevated into requirements.

- No new deadlines, bandwidth values, failover detection policy, controller state-transition behavior or implementation-level security algorithm is invented. Stated failover/support properties are requirements, not proof of successful analysis.

- Properties mentioning shore-sensor data do not establish direct APS-to-SSS or RCC-to-SSS connections; those connections are commented out in the fixed source.

- Navigation GNSS/IMU units share one RTK-UHF unit; the requirements do not claim full independence or single-fault tolerance of the resulting sensing chain.

- Source README warns that the repository may not reflect the latest architecture.

- No published-paper text or full PDF is redistributed in this draft.

- These are boundary flow declarations and author-stated receive intent. ECT.aadl connects its internal emergency module's wireless port outward to RadioCommunication, but does not establish the corresponding inbound path from the ECT boundary to that module. End-to-end reception is not claimed.

## Provenance

Source project: [ahmed-amro/APS-Communication_Architecture](https://github.com/ahmed-amro/APS-Communication_Architecture/tree/2cb6cfc34b2b7c5c0fc5b76b7a5ab0822e60368e).
Pinned source commit: `2cb6cfc34b2b7c5c0fc5b76b7a5ab0822e60368e`.

The prior paper is distinct from the architecture, but this draft is sourced from the fixed model and its embedded requirement properties. All embedded author statements remain model-associated and unsuitable as independent evaluation inputs.

Upstream GPLv3 LICENSE retained; no relicensing under the A3D collection license.

The original mining-time commit of the A3D instance is unknown. The requirement-to-source mapping describes the pinned upstream snapshot, not a recovered historical stakeholder specification.

Line-level traceability: `example/reference/public/a3d/requirements/a3d_aps.json`.
