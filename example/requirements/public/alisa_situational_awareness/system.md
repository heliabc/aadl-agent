# MIS System Specification — original requirements

Source: `reference/public/alisa_situational_awareness/SituationalAwarenessSystem/ReqSpec/MIS-SSS.reqdoc`

Original document: `MIS_SSS`; source commit: `2eb11ca21d188fa278421442ee65b2389403e4e5`.

Copyright 2015 Carnegie Mellon University. Distribution Statement A: Approved for Public Release; Distribution is Unlimited.

## SYS_52 (line 7)

Original declaration context: `for ASSASystem::ASSASystem`

The MIS System shall include the Startup system states.

## SYS_634 (line 10)

Original declaration context: `for ASSASystem::ASSASystem`

The MIS System shall include the Operations system state.

## SYS_635 (line 13)

Original declaration context: `for ASSASystem::ASSASystem`

The MIS System shall include the Shutdown system state.

## SYS_54 (line 17)

Original declaration context: `for ASSASystem::ASSASystem`

After a state transition to a new system state the MIS System shall log the new system state entry event.

## SYS_55 (line 21)

After a fault in any state the MIS System shall log the fault event.

## SYS_275 (line 26)

The MIS System shall ignore state transition events if the event is not defined for the current system state.

## SYS_61 (line 30)

After instantiation by the Computing Platform the MIS System shall enter the Startup State.

## SYS_445 (line 34)

After entering the Startup State the MIS System shall establish requestresponse default connections to the Host Support System for transferring system configuration data.

## SYS_446 (line 38)

If an attempt to establish a default connection to the Host Support System for system configuration data fails the MIS System shall try resending the request.

## SYS_447 (line 42)

If an attempt to establish a default connection to the Host Support System for system configuration data fails three times the MIS System shall generate a fatal fault.

## SYS_448 (line 46)

After establishing a default connection to the Host Support System for system configuration data the MIS System shall establish a default connection to the Host Support System for transferring logging data.

## SYS_449 (line 50)

If an attempt to establish a default connection to the Host Support System for logging data fails the MIS System shall try resending the request.

## SYS_450 (line 54)

If an attempt to establish a default connection to the Host Support System for logging data fails three times the MIS System shall generate a fatal fault.

## SYS_626 (line 58)

After establishing a default connection to the Host Support System for system logging the MIS System shall establish a default connection to the Host Support System for reporting faults.

## SYS_627 (line 62)

If an attempt to establish a default connection to the Host Support System for reporting faults fails the MIS System shall try resending the request.

## SYS_628 (line 66)

If an attempt to establish a default connection to the Host Support System for reporting faults fails three times the MIS System shall generate a fatal fault.

## SYS_452 (line 70)

After a default connection to the Host Support System for system configuration data is established the MIS System shall send a request for system configuration data to the Host Support System.

## SYS_453 (line 74)

If an attempt to send a request for system configuration data to the Host Support System fails the MIS System shall retry sending the request up to a total of three attempts.

## SYS_454 (line 78)

If an attempt to send the request for system configuration data to the Host Support System fails three times the MIS System shall generate a fatal fault.

## SYS_455 (line 82)

After sending the request for system configuration data to the Host Support System the MIS System shall attempt to receive system configuration data from the Host Support System.

## SYS_457 (line 86)

If an attempt to receive system configuration data from the Host Support System fails due to time out the MIS System shall make repeated attempts to send a request for system configuration data to the Host Support System until three total attempts have been made.

## SYS_639 (line 90)

If an attempt to receive system configuration data from the Host Support System fails three times the MIS System shall generate a fatal fault.

## SYS_458 (line 94)

If an attempt to receive system configuration data from the Host Support System fails for a reason other than time out the MIS System shall generate a fatal fault.

## SYS_283 (line 98)

The MIS System shall validate the system configuration data integrity.

## SYS_285 (line 102)

If configuration data is not valid the MIS System shall generate a fatal fault.

## SYS_286 (line 106)

After validating MIS System configuration data the MIS System shall attempt to establish connections with external systems.

## SYS_459 (line 110)

Before an attempt to initialize an AST System the MIS System shall evaluate the connection status to that AST System.

## SYS_287 (line 116)

If determining that an attempt to establish a connection to an AST has failed and at least one AST connection was successful the MIS System shall disable functionality dependent on the failed AST System.

## SYS_460 (line 120)

If functionality associated with an AST System has been disabled due to connection failure the MIS System shall send a fault report to the Host Support System.

## SYS_461 (line 124)

If attempts to establish connections with all AST Systems have failed the MIS System shall generate a fatal fault.

## SYS_444 (line 128)

After establishing configured connections with AST Systems AST interfaces shall be initialized based on configuration data.

## SYS_480 (line 132)

After establishing AST connections the status of AST initialization shall be periodically checked.

## SYS_608 (line 136)

If the duration of the Startup State exceeds an elapsed time threshold specified in system configuration data as a value between 1 minute and 20 minutes the status of AST initialization shall be checked.

## SYS_481 (line 140)

If the duration of the Startup State exceeds an elapsed time threshold specified in system configuration data as a value between 1 minutes and 20 minutes and at least one AST interface completes initialization the MIS System shall disable functionality associated with AST Systems which have not completed initialization.

## SYS_482 (line 144)

If the duration of the Startup State exceeds an elapsed time threshold specified in system configuration data as a value between 1 minutes and 20 minutes and at least one AST interface completes initialization the MIS System shall send a fault report to the Host Support System.

## SYS_65 (line 148)

If the duration of the Startup State exceeds an elapsed time threshold specified in system configuration data as a value between 1 minutes and 20 minutes and at least one AST interface completes initialization the MIS System shall transition to the Operations State.

## SYS_63 (line 152)

If the duration of the Startup State exceeds an elapsed time threshold specified in system configuration data as a value between 1 minutes and 20 minutes and all AST interfaces fail to complete initialization the MIS System shall generate a fatal fault.

## SYS_290 (line 156)

If a nonfatal fault occurs in the Startup State the MIS System shall complete corrective action to resume normal operations within 1 second.

## SYS_442 (line 160)

If a fatal fault occurs in the Startup State and a default connection to the Host Support System for logging is established the MIS System shall report a fatal fault to the Host Support System within 100 milliseconds.

## SYS_67 (line 164)

After a fatal fault has been reported to the Host Support System the MIS System shall transition to the Shutdown State.

## SYS_640 (line 168)

If a fatal fault occurs and a default logging connection to the Host Support System has not been established the MIS System shall transition to the Shutdown State.

## SYS_69 (line 172)

If in the Operations State and a fatal fault occurs the MIS System shall transition to the Shutdown State.

## SYS_70 (line 176)

If in the Operations State and a Shutdown command is received from a Client System the MIS System shall transition to the Shutdown State.

## SYS_73 (line 180)

Functions under the Operations State shall operate concurrently without inhibiting any of the other functions.

## SYS_644 (line 184)

After entering the Shutdown State the MIS System shall complete shutdown activities within a period of 50 milliseconds.

## SYS_645 (line 188)

After completing activities within the Shutdown State the MIS System shall send a MIS Shutdown Complete message to the Host Support System indicating completion of the MIS shutdown sequence.

## SYS_98 (line 192)

The MIS System shall evaluate cached data for staleness at a multiple of a 1 millisecond period between 100 and 1000 milliseconds inclusive based on a time frame defined in MIS system configuration data.  

## SYS_415 (line 196)

While in the Operations State the MIS System shall store transformed data received from AST sources.

## SYS_97 (line 200)

The MIS System shall provide Client Systems requested data stored within the MIS System.

## SYS_416 (line 204)

After a Client request for a data set based on filtering parameters the MIS System shall select the associated data set from data stored within the system.

## SYS_507 (line 208)

After a message is transformed into a SourceTrack data format the MIS System shall store the data.

## SYS_508 (line 212)

After SourceTrack data is stored the MIS System shall determine whether a new threat alert should be created.

## SYS_519 (line 216)

After entering the Operations State the MIS System shall periodically evaluate alerts according to a periodic schedule defined by system configuration data to determine whether they should be deactivated.

## SYS_520 (line 220)

After identifying an obsolete alert the MIS System shall deactivate the alert.

## SYS_267 (line 224)

The MIS System shall marshal and assemble data provided from distinct AST sources into MIS API messages.

## SYS_268 (line 228)

The MIS System shall perform data conversions required to transform AST messages into MIS API messages.

## SYS_269 (line 232)

The MIS System shall perform unit conversions required to transform AST messages into MIS API messages.

## SYS_71 (line 236)

While in the Operations State the MIS System shall perform periodic healthstatus monitoring at a multiple of a 1 millisecond period between 100 and 1000 milliseconds inclusive as defined by system configuration data.

## SYS_328 (line 240)

While in the Operations State the MIS System shall perform a periodic determination of connection status of external AST systems at a 1 second period.

## SYS_487 (line 244)

If an attempt to obtain the status of an external connection fails the MIS System shall send a fault report to the Host Support System.

## SYS_483 (line 248)

The MIS System shall periodically evaluate cached health and status data for staleness at a multiple of a 1 millisecond period between 100 and 1000 milliseconds inclusive based on MIS System configuration data.

## SYS_408 (line 252)

After receiving an AST control message the MIS System shall evaluate the message to apply the associated control to the associated AST within 5 seconds.

## SYS_611 (line 256)

After receiving an MIS control message the MIS System shall evaluate the message to apply the associated control to the MIS System within 5 seconds.

## SYS_127 (line 260)

As part of the MIS_Client_SA_Data_Interface interface the MIS System shall provide an Alert System capability interface that exchanges all alert data with the Client relevant to SA operations warnings cautions andor annunciations.

## SYS_509 (line 264)

After the MIS System determines that a new threat alert should be created the MIS System shall attempt to send a ThreatAlert Message to the Client System.

## SYS_510 (line 268)

If an attempt to send a ThreatAlert Message to the Client System fails the MIS System shall send a fault report to the Host Support System.

## SYS_126 (line 272)

As part of the MIS_Client_SA_Data_Interface interface the MIS System shall provide a Collision Detection capability interface that exchanges all data with the Client relevant to the detection of potential platform terrain collisions.

## SYS_125 (line 276)

As part of the MIS_Client_SA_Data_Interface interface the MIS System shall provide a Platform Air Vehicle capability interface for exchanging all available data with the Client relevant to the current aircraft state including platform position payload and flight dynamics.

## SYS_123 (line 280)

As part of the MIS_Client_SA_Data_Interface interface the MIS System shall provide a Threat Detection capability interface for exchanging all available data with the Client relevant to detected platform threats.

## SYS_511 (line 284)

After entering the Operations State the MIS System shall periodically attempt to receive a SA_DataRequest Message from a Client System with the period defined by system configuration data.

## SYS_513 (line 288)

If an attempt to receive a SA_DataRequest Message from a Client System fails due to an error while attempting to receive the MIS System shall send a fault report to the Host Support System.

## SYS_514 (line 292)

After receiving a SA_DataRequest Message from a Client System the MIS System shall check the validity of the data within the message.

## SYS_515 (line 296)

If the data within the SA_DataRequest Message is invalid the MIS System shall send a fault report to the Host Support System.

## SYS_516 (line 300)

After the data within the SA_DataRequest Message is validated the MIS System shall obtain the stored data associated with the request.

## SYS_517 (line 304)

After the MIS System determines the connection associated with the SA_DataResponse Message the MIS System shall attempt to send a SA_DataResponse Message to the Client System.

## SYS_518 (line 308)

If an attempt to send a SA_DataReponse Message to the Client System fails the MIS System shall send a fault report the Host Support System.

## SYS_122 (line 312)

The MIS System shall implement the MIS_Client_SA_Data_Interface in accordance with IAW the Modular Integrated Survivability MIS Application Program Interface Interface Control Document 104710TICD CDRL A002.

## SYS_296 (line 316)

As part of the MIS_Client_Control_Interface interface the MIS System shall provide a Control capability interface for exchanging commands from the Client.

## SYS_521 (line 320)

After entering the Operations State the MIS System shall periodically attempt to receive an AST_ConfigRequest Message from a Client System with a period based on system configuration data.

## SYS_523 (line 324)

If an attempt to receive an AST_ConfigRequest Message fails due to an error receiving the MIS System shall send a fault report to the Host Support System.

## SYS_529 (line 328)

After receiving an AST_ConfigRequest Message from a Client System the MIS System shall check the validity of the data within the message.

## SYS_625 (line 332)

If message data within an AST_ConfigRequest Message is invalid the MIS System shall send a fault report to the Host Support System.

## SYS_530 (line 336)

If message data within an AST_ConfigRequest Message is invalid and a negative acknowledgment is expected the MIS System shall send a negative acknowledgment message to the Client System.

## SYS_531 (line 340)

After message data within an AST_ConfigRequest Message is validated and an acknowledgment is expected the MIS System shall send an acknowledgment message to the Client System.

## SYS_532 (line 344)

If an attempt to send an acknowledgment message to the Client System fails due to time out the MIS System shall retry the attempt to send the message up to a total of three attempts.

## SYS_533 (line 348)

If an attempt to send an acknowledgment message to the Client System fails for a reason other than time out the MIS System shall send a fault report to the Host Support System.

## SYS_534 (line 352)

If the third consecutive attempt to send an acknowledgment message to the Client System fails due to time out the MIS System shall send a fault report the Host Support System.

## SYS_535 (line 356)

After message data within an AST_ConfigRequest Message is validated the MIS System shall read the stored configuration data associated with the AST_ConfigRequest Message.

## SYS_536 (line 360)

After reading configuration data associated with the AST_ConfigRequest Message the MIS System shall attempt to send an AST_ConfigResponse Message to the Client System.

## SYS_537 (line 364)

If an attempt to send an AST_ConfigResponse Message to the Client System fails the MIS System shall retry the attempt to send the message up to a total of three attempts.

## SYS_538 (line 368)

If the third consecutive attempt to send a AST_ConfigResponse Message fails the MIS System shall send a fault report to the Host Support System.

## SYS_539 (line 372)

After entering the Operations State the MIS System shall periodically attempt to receive an AST_Command Message from a Client System.

## SYS_541 (line 376)

If an attempt to receive an AST_Command Message fails due to an error while attempting to receive the MIS System shall send a fault report to the Host Support System.

## SYS_543 (line 380)

After receiving an AST_Command Message from a Client System the MIS System shall check the validity of the data within the message.

## SYS_544 (line 384)

If message data within an AST_Command Message is invalid and a negative acknowledgment is expected the MIS System shall send a negative acknowledgment message to the Client System.

## SYS_623 (line 388)

If message data within an AST_Command Message is invalid the MIS System shall send a fault report to the Host Support System.

## SYS_545 (line 392)

After message data within an AST_Command Message is validated and an acknowledgment is expected the MIS System shall send an acknowledgment message to the Client System.

## SYS_546 (line 396)

After message data within an AST_Command Message is validated the MIS System shall determine the target system the AST_Command Message is associated with.

## SYS_547 (line 400)

After an AST_Command Message is determined to be associated with the WeaponWatch System the MIS System shall transform the AST_Command data into a WeaponWatch SimpleCommand Message format.

## SYS_559 (line 404)

After message data within an CommandApplied Message is validated the MIS System shall transform the CommandApplied data into a ClientCmdApplied data format.

## SYS_560 (line 408)

After transforming data into a ClientCmdApplied Message format the MIS System shall attempt to send a ClientCmdApplied Message to the Client System.

## SYS_561 (line 412)

After creating a ClientCmdApplied Message containing a negative command applied message the MIS System shall attempt to send a ClientCmdApplied Message to the Client System.

## SYS_562 (line 416)

If an attempt to send a ClientCmdApplied Message to the Client System fails the MIS System shall retry the attempt to send the message up to a total of three attempts.

## SYS_563 (line 420)

If the third consecutive attempt to send ClientCmdApplied Messages fails the MIS System shall send a fault report to the Host Support System.

## SYS_564 (line 424)

After entering the Operations State the MIS System shall periodically attempt to receive a SystemCommand Message from a Client System with a period based on system configuration data.

## SYS_565 (line 428)

If an attempt to receive a SystemCommand Message fails due to time out the MIS System shall attempt to receive a SystemCommand Message.

## SYS_566 (line 432)

If an attempt to receive a SystemCommand Message fails for a reason other than time out the MIS System shall send a fault report to the Host Support System.

## SYS_568 (line 436)

After receiving a SystemCommand Message from a Client System the MIS System shall check the validity of the data within the message.

## SYS_569 (line 440)

If message data within a SystemCommand Message is invalid and a negative acknowledgment is expected the MIS System shall send a negative acknowledgment message to the Client System.

## SYS_621 (line 444)

If message data within a SystemCommand Message is invalid the MIS System shall send a fault report to the Host Support System.

## SYS_570 (line 448)

After message data within a SystemCommand Message is validated and an acknowledgment is expected the MIS System shall send an acknowledgment message to the Client System.

## SYS_571 (line 452)

After message data within a SystemCommand Message is validated the MIS System shall determine the type of system command requested.

## SYS_572 (line 456)

After determining that a Shutdown system command has been received and the MIS System is in the Operations State the MIS System shall generate an event triggering a transition to the Shutdown State.

## SYS_412 (line 460)

The MIS System shall implement the MIS_Client_Control_Interface in accordance with IAW the Modular Integrated Survivability MIS Application Program Interface Interface Control Document 104710TICD CDRL A002.

## SYS_488 (line 464)

While in the Operations State the MIS System shall periodically attempt to receive HealthStatusRequest Messages from a Client System using the MIS_Client_Health_Status_Interface interface with a period based on system configuration data.

## SYS_490 (line 468)

If an attempt to receive a HealthStatusRequest Message from a Client System fails due to an error while attempting to receive the MIS System shall send a fault report to the Host Support System.

## SYS_493 (line 472)

After receiving a HealthStatusRequest Message from a Client System the MIS System shall check the validity of the data within the message.

## SYS_494 (line 476)

If data within the HealthStatusRequest Message is invalid the MIS System shall send a fault report to the Host Support System.

## SYS_495 (line 480)

After the data in the HealthStatusRequest Message is validated the MIS System shall read the stored status.

## SYS_496 (line 484)

After the MIS System reads the stored status the MIS System shall determine the response connection for the reply to the HealthStatusRequest Message from the Client.

## SYS_497 (line 488)

After the MIS System determines the connection associated with the HealthStatusResponse Message the MIS System shall send the HealthStatusResponse Message.

## SYS_498 (line 492)

If an attempt to send a HealthStatusResponse Message fails the MIS System shall send a fault report to the Host Support System.

## SYS_233 (line 496)

The MIS System shall adhere to message types and formats as defined in the SEO Communication Message Format Standards 2.0 Revision H August 22 2012 for the MIS_WW_Interface interface.

## SYS_25 (line 500)

After the WeaponWatch System sends a Hostile Fire Detection Message to the MIS System the MIS System shall attempt to receive the message in the format defined in the SEO Communications Message Format Standard ICD section 4.8.

## SYS_501 (line 504)

If an attempt to receive a HostileFireDetection Message fails due to an error while attempting to receive the MIS System shall send a fault report to the Host Support System.

## SYS_502 (line 508)

After receiving a HostileFireDetection Message from the WeaponWatch System the MIS System shall perform a cyclic redundancy check on the received message.

## SYS_503 (line 512)

After passing a cyclic redundancy check for a HostileFireDetection Message the MIS System shall check the validity of the data within the message.

## SYS_504 (line 516)

If the data in the HostileFireDetection Message is invalid the MIS System shall send a fault report to the Host Support System.

## SYS_505 (line 520)

After the data in the HostileFireDetection Message received from the WeaponWatch System is validated and an acknowledgment is expected the MIS System shall send an acknowledgment message to the WeaponWatch System.3

## SYS_506 (line 524)

After the data in the HostileFireDetection Message received from the WeaponWatch System is validated the HostileFireDetection Message shall be transformed into a common SourceTrack data format.

## SYS_462 (line 528)

After starting initialization of the WeaponWatch AST System the MIS System shall verify the configured connection to the WeaponWatch System as being established.

## SYS_428 (line 532)

After obtaining the current MIS System configuration data and the WeaponWatch System is specified in the configuration the MIS System shall establish communications with WeaponWatch.

## SYS_463 (line 536)

After starting initialization of the WeaponWatch AST System and the associated connection is verified as established the MIS System shall attempt to receive a Ping Message from the WeaponWatch System.

## SYS_464 (line 540)

If an attempt to receive a Ping Message from the WeaponWatch System fails due to time out the MIS System shall make repeated attempts to receive a Ping Message.

## SYS_465 (line 544)

If an attempt to receive a Ping Message from the WeaponWatch System fails for a reason other than time out the MIS System shall send a fault report to the Host Support System.

## SYS_466 (line 548)

After receiving a Ping Message from the WeaponWatch System the MIS System shall perform a cyclic redundancy check on the received message.

## SYS_468 (line 552)

If a cyclic redundancy check for a message received from the WeaponWatch System fails and a negative acknowledgment is expected the MIS System shall send a negative acknowledgment message to the WeaponWatch System.

## SYS_469 (line 556)

If an attempt to send a negative acknowledgment message to the WeaponWatch System fails the MIS System shall send a fault report to the Host Support System.

## SYS_467 (line 560)

After passing a cyclic redundancy check for a Ping Message received from the WeaponWatch System the MIS System shall send an acknowledgment message to the WeaponWatch System.

## SYS_470 (line 564)

If an attempt to send a acknowledgment message to the WeaponWatch System fails the MIS System shall send a fault report to the Host Support System.

## SYS_471 (line 568)

After passing a cyclic redundancy check for a Ping Message received from the WeaponWatch System the MIS System shall attempt to receive a StatusHealth Message from the WeaponWatch System.

## SYS_472 (line 572)

If an attempt to receive a StatusHealth Message from the WeaponWatch System fails due to time out the MIS System shall make repeated attempts to receive a StatusHealth Message.

## SYS_473 (line 576)

If an attempt to receive a StatusHealth Message from the WeaponWatch System fails for a reason other than time out the MIS System shall send a fault report to the Host Support System.

## SYS_474 (line 580)

After receiving a StatusHealth Message from the WeaponWatch System the MIS System shall perform a cyclic redundancy check on the received message.

## SYS_475 (line 584)

After passing a cyclic redundancy check for a StatusHealth Message from the WeaponWatch System the MIS System shall check the validity of the data within the message.

## SYS_476 (line 588)

If the data in a StatusHealth Message received is invalid the MIS System shall send a fault report to the Host Support System.

## SYS_477 (line 592)

After the data in the StatusHealth Message received from the WeaponWatch System is validated and an acknowledgment is expected the MIS System shall send an acknowledgment message to the WeaponWatch System.

## SYS_478 (line 596)

After the data in the StatusHealth Message received from the WeaponWatch System is validated and the MIS System is in the Startup State the status of the WeaponWatch System shall be stored.

## SYS_479 (line 600)

After a healthy status of the WeaponWatch System is stored the count of initialized AST systems shall be updated.

## SYS_32 (line 604)

While in the Operations State the MIS System shall periodically receive a status from the WeaponWatch system using the HealthStatus Message with a period based on system configuration data.

## SYS_484 (line 608)

After the data in the StatusHealth Message received from the WeaponWatch System is validated and if the MIS System is in the Operations State the StatusHealth Message shall be transformed into a common health data format.

## SYS_485 (line 612)

After a message is transformed into a common health data format the MIS System shall store the data.

## SYS_486 (line 616)

If a cyclic redundancy check for message received from the WeaponWatch System fails and if the MIS System is in the Operations State the MIS System shall send a fault report to the Host Support System.

## SYS_34 (line 620)

After reception of a message from the WeaponWatch System the MIS System shall send a message acknowledgment to the WeaponWatch system.

## SYS_548 (line 624)

After transforming data into a WeaponWatch SimpleCommand Message format the MIS System shall attempt to send a SimpleCommand Message to the WeaponWatch System.

## SYS_549 (line 628)

If an attempt to send a SimpleCommand Message to the WeaponWatch System has failed the MIS System shall retry the attempt to send the message up to a total of three attempts.

## SYS_550 (line 632)

If the third consecutive attempt to send a SimpleCommand Message to the WeaponWatch system has failed the MIS System shall send a fault report to the Host Support System.

## SYS_551 (line 636)

After sending a SimpleCommand Message to the WeaponWatch System the MIS System shall make repeated attempts to receive a CommandApplied Message from the WeaponWatch System for a predetermined time period.

## SYS_552 (line 640)

If an attempt to receive a CommandApplied Message from the WeaponWatch System fails for a reason other than time out the MIS System shall send a fault report to the Host Support System.

## SYS_553 (line 644)

If an attempt to receive a CommandApplied Message from the WeaponWatch System fails and the associated time period has not expired the MIS System shall make a repeated attempt to send a SimpleCommand Message to the WeaponWatch System until the time period has expired or the attempt succeeds.

## SYS_554 (line 648)

If all permitted attempts to receive a CommandApplied Message from the WeaponWatch System have failed and the associated time period has expired the MIS System shall create a ClientCmdApplied Message containing a negative command applied status.

## SYS_555 (line 652)

After receiving a CommandApplied Message from the WeaponWatch System the MIS System shall perform a cyclic redundancy check on the received message.

## SYS_556 (line 656)

After passing a cyclic redundancy check for a CommandApplied Message the MIS System shall check the validity of the data within the message.

## SYS_618 (line 660)

If a cyclic redundancy check for a CommandApplied Message fails the MIS System shall send a fault report to the Host Support System.

## SYS_557 (line 664)

If message data within a CommandApplied Message is invalid and a negative acknowledgment is expected the MIS System shall send a negative acknowledgment message to the WeaponWatch System.

## SYS_631 (line 668)

If a cyclic redundancy check for a CommandApplied Message fails and an attempt to send a negative acknowledgment message to the WeaponWatch System fails the MIS System shall retry the attempt to send the message up to a total of three attempts.

## SYS_632 (line 672)

If message data within a CommandApplied Message is invalid and an attempt to send a negative acknowledgment message to the WeaponWatch System fails the MIS System shall retry the attempt to send the message up to a total of three attempts.

## SYS_619 (line 676)

If message data within a CommandApplied Message is invalid the MIS System shall send a fault report to the Host Support System.

## SYS_558 (line 680)

After message data within a CommandApplied Message is validated and an acknowledgment is expected the MIS System shall send an acknowledgment message to the WeaponWatch System.

## SYS_633 (line 684)

If Message data within a CommandApplied Message is validated and an attempt to send an acknowledgement message to WeaponWatch fails the MIS System shall retry the attempt to send the message up to a total of three attempts.

## SYS_429 (line 688)

If a communications failure with WeaponWatch occurs the MIS System shall disable functionality dependent on WeaponWatch.

## SYS_590 (line 692)

The MIS System shall adhere to message types and formats as defined in the JATAS ICD06424TD112301 Rev. D Appendix G for the MIS_JATAS_Native_SA_Data_Interface interface.

## SYS_591 (line 696)

The MIS System shall attempt to receive the AMWS Threat Message from the JATAS System at a 20 milliseconds period.

## SYS_592 (line 700)

The MIS System shall attempt to receive the AMWS  DIRCM Status Message to determine the JATAS healthstatus state at a 667 millisecond period.

## SYS_593 (line 704)

The MIS System shall attempt to receive a Hostile Fire Indication HFI Threat Report Message from the JATAS System at a 50 millisecond period.

## SYS_594 (line 708)

The MIS System shall attempt to receive a Laser Warning Report LWR Threat Report Message from the JATAS System at a 50 millisecond period.

## SYS_595 (line 712)

After JATAS initialization the MIS System shall attempt to receive an Output Wrap Message from the JATAS System through the MIS_JATAS_Native_SA_Data_Interface interface.

## SYS_596 (line 716)

After receiving the Output Wrap Message from the JATAS System the MIS System shall respond by sending the Input Wrap Message to the JATAS System through the MIS_JATAS_Native_SA_Data_Interface interface.

## SYS_598 (line 720)

After obtaining the current MIS System configuration data and the JATAS System is specified in the configuration the MIS System shall attempt to establish communications with JATAS.

## SYS_599 (line 724)

After communications failure with JATAS the MIS System shall disable functionality dependent on JATAS.

## SYS_602 (line 728)

The MIS System shall adhere to message types and formats as defined in the JATAS ICD06424TD112301 Rev. D Appendix F for the MIS_JATAS_Native_Control_Interface interface.

## SYS_603 (line 732)

After receiving a Client AST Command Message the MIS System shall send an ANAAR59 Mode Command Message to the JATAS System.

## SYS_604 (line 736)

After JATAS initialization the MIS System shall attempt to receive an Output Wrap Message from the JATAS System through the MIS_JATAS_Native_Control_Interface interface.

## SYS_605 (line 740)

After receiving the Output Wrap Message from the JATAS System the MIS System shall respond by sending the Input Wrap Message to the JATAS System through the MIS_JATAS_Native_Control_Interface interface.

## SYS_606 (line 744)

The MIS System shall attempt to receive the ANAAR59 Status Message to determine the JATAS healthstatus state at a 800 millisecond period.

## SYS_239 (line 748)

The MIS System shall access configuration data through the Host Support System.

## SYS_240 (line 752)

The MIS System shall report logging data through the Host Support System.

## SYS_105 (line 756)

The MIS System shall provide health and status reports to the Host Support System.

## SYS_106 (line 760)

After a fault event the MIS System shall send a fault report to the Host Support System within 1 second.

## SYS_609 (line 764)

The MIS System shall enable and disable system functions based on the selected mission equipment package configuration

## SYS_610 (line 768)

The MIS System shall operate such that the removal of system functions not required for a specific mission does not adversely impact the remainder of the MIS System. 

## SYS_271 (line 772)

The MIS System shall be qualified and documented to support formulation of an AQSR in accordance with AR 7062 para 31 through 34 to support Army Materiel Release per AR 700142

## SYS_167 (line 776)

The MIS API shall be modular such that it is organized into cohesive groups of operations that can be selectively implemented or instantiated based on the capability required by the MIS Systems host aircraft platform.  

## SYS_166 (line 780)

The MIS System shall exhibit modular design with functionality decomposed into cohesive architectural elements.

## SYS_638 (line 784)

The MIS System shall exhibit modular design with functionality decomposed into loosely coupled architectural elements.

## SYS_165 (line 788)

The MIS System shall have a modular design such that complete airworthiness requalification of the system is not necessary upon adding a new AST System.

## SYS_164 (line 792)

The MIS System shall have a modular design such that complete airworthiness requalification of the system is not necessary upon replacing an existing AST System that integrates with the MIS System.

## SYS_163 (line 796)

The MIS System shall have a modular design such that complete airworthiness requalification of the system is not necessary upon removing a currently integrated AST System.

## SYS_162 (line 800)

The MIS System shall have a modular design such that complete airworthiness requalification of the system is not necessary upon modifying a currently integrated AST System.

## SYS_161 (line 804)

The MIS System shall allow changes to nonsafety critical software units independent of safety critical software units.

## SYS_160 (line 808)

The MIS System shall be configurable to support inclusion or exclusion of prespecified AST Systems without recompiling the entire MIS System code base.

## SYS_174 (line 812)

The MIS System shall access operating system functions through an abstraction layer to facilitate porting of the software between FACE conformant operating systems.

## SYS_173 (line 816)

The MIS System shall access communication services not otherwise defined by the operating system through an abstraction layer to facilitate software porting across aircraft operating environments that use different protocols.

## SYS_259 (line 820)

The MIS System software shall execute within a Green Hills INTEGRITY178B operating system environment. 

## SYS_170 (line 824)

The MIS System shall execute within a WindRiver VxWorks653 operating system environment. 

## SYS_178 (line 828)

The MIS System shall provide required isolationpartitioning when mixed criticality software units are used within the system.

## SYS_272 (line 832)

The MIS System shall be designed to provide isolation for software variability to ensure that system parts that frequently change are segregated from parts that seldom change to reduce requalification expense.

## SYS_177 (line 836)

The ROSAS  MIS project will consider FAA advisory circular AC 20148 entitled Reusable Software Components in the systems specification design prototyping and certification approach.

## SYS_187 (line 840)

The MIS System shall meet host aircraft platform performance goals for data latency.

## SYS_186 (line 844)

The MIS System shall meet host aircraft platform performance goals for determinism.

## SYS_185 (line 848)

The MIS System shall meet host aircraft platform performance goals for fault tolerance.

## SYS_182 (line 852)

The MIS System shall only use the cockpit display hardware that exists on the aircraft platform into which it is integrated.

## SYS_181 (line 856)

The MIS System shall support deployment on an available processing resource within an existing mission computer or as a standalone avionics system.

## SYS_198 (line 860)

The MIS API shall be a government owned open and nonproprietary data messaging standard.

## SYS_197 (line 864)

The MIS System shall reduce aircraft platform dependence on proprietary AST software by encapsulating such softwaresystems with the MIS API.

## SYS_196 (line 868)

The MIS System shall use widely supported open standards for designated key interfaces.

## SYS_641 (line 872)

The MIS System shall not use vendor unique extensions for designated key interfaces.

## SYS_195 (line 876)

The MIS System shall only incorporate externally developed software elements that have unlimited data rights to their form fit function and interface design.

## SYS_636 (line 880)

The MIS API shall conform to the FACE Data Model.

## SYS_201 (line 883)

The MIS System software shall be implemented in CC programming languages.

## SYS_642 (line 888)

The software implementation of the MIS System shall be compliant with MIS coding standards.

## SYS_643 (line 892)

The software design of the MIS System shall be compliant with MIS design standards.

## SYS_704 (line 896)

The MIS System shall consist of only system components that are allocated to exactly one FACE architectural segment where segments are defined in the Technical Standard for FACE Edition 2.0.

## SYS_713 (line 900)

For the MIS System each transaction between an IO Service and Platform Specific Services Segment system component shall consist of a single IO message instance.

## SYS_714 (line 904)

The MIS System Platform Device Services subsegment system components shall use the IO message instance which includes the following fields GUID  The Message GUID uniquely identifies each instance of the Message Instance entity. BUS TYPE  Defines the type of avionics bus that the message is associated with ARINC 429 MILSTD1553 Serial etc.. PAYLOAD LENGTH  Defines the size of message payload in bytes. MESSAGE TYPE  Defines the type of message being transmitted. Indicates whether the message is a command message to or from the IO Service a status message to or from the IO Service e.g. health status bit or a data transaction to or from the IO Service.  CMST FIELD  Provides the Command or Status depending on the message type. TIMESTAMP REQUIRED  Indicates that time stamping of the message instance is required. If set then the timestamp field is filled out. TIMESTAMP  Contains the time the data was received. This field contains a valid time if the TIMESTAMP REQUIRED field is set. PAYLOAD DATA  Contains the IO unique payload. Each payload may have additional IO unique header information.

## SYS_715 (line 908)

MIS System Platform Device Services subsegment system components shall communicate messages through the IO Interface in the format defined in Section D.11

## SYS_727 (line 912)

The MIS System system components shall communicate logging messages using the Transport Services API or the IO Interface API with the message data format based on IETF RFC 5424 The Syslog Protocol. 

## SYS_653 (line 916)

The MIS System shall include system components allocated to PlatformSpecific Device Services subsegment that provide control for receive data from and send data to a platform device or other external AST systems when such external systems provide interfaces that do not conform to the FACE Data Model .

## SYS_654 (line 920)

The MIS System Platform Specific Services Segment system components shall only use the interfaces defined in Section 3.4  Section 3.7 Section 3.11  Section 3.12 Section 3.13 and Section 3.16 sections of the Technical Standard for FACE Edition 2.0.

## SYS_648 (line 924)

The MIS System communications with platform devices external components and systems whose data is not represented in the FACE Data Model shall be achieved through the IO Services Interface.

## SYS_649 (line 928)

The MIS System shall translate between messages conformant to the FACE Data Model and platformspecific data platform device or external system that is not represented within the FACE Data Model  within system components allocated to the PlatformSpecific Services Segment and outside the IO Interface library.

## SYS_650 (line 932)

The MIS System shall use the Transport Services Interface for interpartitionprocess or intrapartitionprocess communications between system components within the Platform Specific Services Segment with the exception of Data Protocol Mediation components.

## SYS_655 (line 936)

The MIS System data descriptions for the Transport Services Interface shall be documented and available.

## SYS_656 (line 940)

The MIS System PlatformSpecific Services Segment system components shall provide all Transport Services Segment data over the Transport Services Interface in conformance with the FACE Data Model per Section 3.6 and 3.7 of the Technical Standard for FACE Edition 2.0.

## SYS_657 (line 944)

The MIS System PlatformSpecific Services Segment system components shall use the Transport Services Interface and the mechanisms provided by the Transport Services Segment to transmit data to Portable Components Segment system components.

## SYS_658 (line 948)

The MIS System shall use the IO Services Interface IO Interface for all communication with the IO Services Segment.

## SYS_659 (line 952)

The MIS System shall format all messages communicated through the IO Services Interface API as defined in section D.11 of the Technical Standard for FACE Edition 2.0.

## SYS_660 (line 956)

The MIS System shall use the interfaces defined in Section 3.11 Section 3.12 or Section 3.13 of the Technical Standard for FACE Edition 2.0 for all system components of the PSS Segment when accessing functions provided by the Operating System Segment.

## SYS_661 (line 960)

The MIS System PlatformSpecific Device Services subsegment system components shall include platformspecific functionality configuration information.

## SYS_662 (line 964)

The MIS System PlatformSpecific Device Services subsegment system components shall be configurable for platformspecific and missionspecific functionality through platform and mission configuration information.

## SYS_663 (line 968)

The MIS System PlatformSpecific Services Segment system components shall be accessible by multiple system consumers and producers of data.

## SYS_664 (line 972)

Each MIS System system component  i.e. FACEconformant UoP shall provide a data model known as the UoP Data Model when the system component is submitted for FACE conformance. 

## SYS_665 (line 976)

The MIS System system components UoP Data Model shall be XML Metadata Interchange XMI that conforms to the Essential MetaObject Facility EMOF metamodel as specified by EMOF 2.0 OMG.

## SYS_717 (line 980)

The MIS System system components UoP Data Model shall use the xmiid attribute provided by XMI with a unique Universally Unique Identifier UUID as the ID for all elements.

## SYS_718 (line 984)

The MIS System system components UoP Data Model shall validate against the Object Constraint Language OCL constraints managed by the FACE Data Model CCB as specified by OCL 2.3.1 OMG

## SYS_719 (line 988)

The MIS System system components UoP Data Model  shall conform to the metamodel specified in Appendix G.

## SYS_720 (line 992)

The MIS System system components UoP Data Model shall include definitions of all messages sent to or received from the TSS API.

## SYS_721 (line 996)

The MIS System system components UoP Data Model shall include all elements identified as messages sent or received by the UoP as well as all elements upon which those messages depend including PDM LDM and CDM Elements and Views.

## SYS_722 (line 1000)

Any governed elements submitted with the UoP Data Model shall be consistent with the elements in the Shared Data Model that have been approved by the FACE Data Model CCB.

## SYS_716 (line 1004)

A MIS System Portable Component Segment system component deliverable shall exclusively use the Transport Services Interface for data exchanges with the remainder of the FACE environment.

## SYS_724 (line 1008)

A MIS System Portable Component Segment system component shall exclusively use the FACE specified operating system runtime or framework APIs for accessing operating system segment functions.

## SYS_667 (line 1012)

MIS System Portable Components Segment system components shall access Platform Specific Services Segment system components through the Transport Services Interface. 

## SYS_668 (line 1016)

MIS System system component FACE UoPs shall include all software providing the components service or capability i.e. nonstandard runtime layers and component frameworks.

## SYS_669 (line 1020)

The MIS System system component FACE UoP boundary shall exist entirely in a single FACE architectural segment.

## SYS_670 (line 1024)

The MIS System system component FACE UoP boundary shall exist entirely in a single partition.

## SYS_671 (line 1028)

MIS System system components FACE UoPs shall be restricted to using the following interfaces for all data interchanges that traverse the components boundary per the FACE segment goals The Transport Services Interface The IO Interface The Runtime Interfaces The Framework Interfaces The Operating System Interfaces The Health Management Fault Monitoring Interfaces

## SYS_672 (line 1032)

MIS System system components FACE UoPs shall provide one or more services or missionlevel capabilities 

## SYS_673 (line 1036)

If a component framework is implemented in the Platform Specific Services Segment as part of a MIS System system component UoP implementation then the component framework shall conform exclusively to any subset of the following interfaces for all data flows crossing the MIS System system component UoP boundary FACE TS Interface FACE IO Interface FACE OS Interface FACE Runtime Interfaces NonFACE Runtime Interfaces available within the same UoP

## SYS_674 (line 1040)

If a component framework is implemented in the Portable Component Segment as part of  a MIS System system component UoP then the component framework shall conform exclusively to a subset of the following interfaces for all data flows crossing the MIS System system component UoP boundary FACE TS Interface FACE OS Interface FACE Runtime Interfaces NonFACE Runtime Interfaces available within the same UoP

## SYS_705 (line 1044)

If a MIS System system component within the Platform Specific Services Segment uses the operating system to execute then the system component shall use the FACE OS Interface.

## SYS_706 (line 1048)

If a MIS System system component within the Portable Components Segment uses the operating system to execute then the system component shall use the FACE OS Interface.

## SYS_675 (line 1052)

The MIS System shall use the Transport Services Interface for communication between Portable Component Segment UoPs system components.

## SYS_676 (line 1056)

The MIS System shall use the Transport Services Interface for communication between Platform Specific Services Segment system components UoPs except as noted in Section 3.5 of the Technical Standard for FACE Edition 2.0.

## SYS_677 (line 1060)

If the MIS System uses a nonstandard framework or runtime then the nonstandard framework or runtime shall always be contained within a system component UoP and conform to the FACE defined interfaces for the segment in which it is deployed.

## SYS_678 (line 1064)

If the Transport Services Interface is not used as the sole communication mechanism between a set of MIS System application implementation elements then the set of application implementation elements shall be included within and bound to a single system component UoP. 

## SYS_679 (line 1068)

The MIS System shall exclusively use the Transport Services Interfaces as depicted in Figure 52 of the Technical Standard for FACE Edition 2.0 for all communication between UOPs.

## SYS_707 (line 1072)

For MIS System system components that are implmented using the C programming language the C programming language shall comprise Programming language features described in ANSIISOIEC9899 1999 Programming Languages  C except for the following Application use of the pragma directive Section 6.10.6. The C library functions Section 7 that are supported are the C library functions that are defined in corresponding FACE Safety POSIX Profile library functions share common definitions between POSIX and ANSIISOIEC9899 1999.

## SYS_708 (line 1076)

For MIS System system components that are implmented using the C programming language the C programming language shall comprise restrictions to programming language features and libraries that are in accordance with RTCA DO178C supplement DO332 ObjectOriented Technology. 

## SYS_711 (line 1080)

For MIS System system components that are implmented using the C programming language the C programming language shall comprise programming language features described in ISOIEC 148822003 Programming Languages  C except for the following Application use of the pragma directive Section 16.6. The C library functions that are supported are only those that are in common with the C library functions defined in the corresponding FACE Safety POSIX Profile. Inputoutput library Section 27. There are no inputoutput library functions supported.

## SYS_681 (line 1084)

The MIS System software components UoP shall use configuration information when required to initialize parameters of the software component or to enable or disable certain software component capabilities.

## SYS_682 (line 1088)

The MIS System parameters or capabilities which are configurable shall be defined in the specification for each system component. The schema data elements for configuration format are the following Data elements and structure are defined using XSD Descriptive information Data elements are constrained using XSD Data types Limits Precision Valid values XML Schema Version

## SYS_709 (line 1092)

The MIS System system components shall support configuration at build compilation time.

## SYS_710 (line 1096)

The MIS System system components shall support configuration at initialization time.

## SYS_703 (line 1100)

The MIS System system components shall use a Centralized Configuration Service to receive configuration information.

## SYS_684 (line 1104)

The MIS System shall communicate with the Centralized Configuration Service using the Transport Services Interface.

## SYS_689 (line 1108)

The MIS System shall provide nonconflicting values for each uniquely named shared configuration item within the Centralized Configuration Service.

## SYS_700 (line 1112)

Each MIS System system component shall provide schema information for required configuration data.

## SYS_701 (line 1116)

The MIS System configuration data shall be in accordance with the FACE Data Model defined in Section 3.6 of the Technical Standard for FACE Edition 2.0.

## SYS_702 (line 1120)

The MIS System shall use the Health Management Fault Monitoring interface and capabilities in accordance with Appendix C of the Technical Standard for FACE Edition 2.0.

## SYS_725 (line 1124)

The MIS System shall only use the SafetyBase profile POSIX APIs as defined in appendix A of Technical Standard for FACE Edition 2.0.

## SYS_726 (line 1127)

If fork is called within system compnents of the MIS System the fork shall be followed consecutively by a call to exec.

## SYS_712 (line 1131)

The MIS System system components shall have FACE UoP Data Models that conform to the FACE EMOF Metamodel.

## SYS_637 (line 1135)

Original declaration context: `for MISServices::ASSADataStorage`

The designated FACE software components of the MIS System shall be verified for conformance with the FACE Technical Standard.

## SYS_243 (line 1139)

The MIS System shall be packaged according to the FACE packaging format
