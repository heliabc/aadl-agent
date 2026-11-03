"""AS5506D production-bound Python attributes and static constraints.

The evaluator derives a fresh project declaration environment before checking
references. A missing definition is Pending while the project is open; no
speculative probe mutates syntax nodes or an earlier symbol environment.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
import copy
import hashlib
from functools import lru_cache
import re
from aadl_single_agent.semantic_types import Status, RuleResult, SemanticReport, RuleDefinitionError
from aadl_single_agent.attribute_evaluator import AttributeEvaluator, Missing

STANDARD = "SAE AS5506D (2022-04)"
OSATE_REVISION = "b8308b7"
OSATE_SOURCE = "https://github.com/osate/osate2/tree/b8308b7"

RESERVED = frozenset(['aadlboolean',
 'aadlinteger',
 'aadlreal',
 'aadlstring',
 'abstract',
 'access',
 'all',
 'and',
 'annex',
 'applies',
 'binding',
 'bus',
 'calls',
 'classifier',
 'compute',
 'connections',
 'constant',
 'data',
 'delta',
 'device',
 'end',
 'enumeration',
 'event',
 'extends',
 'false',
 'feature',
 'features',
 'flow',
 'flows',
 'group',
 'implementation',
 'in',
 'inherit',
 'initial',
 'inverse',
 'is',
 'list',
 'memory',
 'mode',
 'modes',
 'none',
 'not',
 'of',
 'or',
 'out',
 'package',
 'parameter',
 'path',
 'port',
 'private',
 'process',
 'processor',
 'properties',
 'property',
 'prototypes',
 'provides',
 'public',
 'range',
 'record',
 'reference',
 'refined',
 'renames',
 'requires',
 'self',
 'set',
 'sink',
 'source',
 'subcomponents',
 'subprogram',
 'system',
 'thread',
 'to',
 'true',
 'type',
 'units',
 'virtual',
 'with'])

# Verbatim pinned OSATE built-in property sets, including their license notices.
# They are parsed as dependency units, not trusted fabricated declarations.
BUILTIN_SOURCES = {'AADL_Project.aadl': '--AADL2\n'
                      '--SAE Aerospace Standard AS5506D\n'
                      '--Appendix A: Predeclared Property Sets\n'
                      '-- @codegen-package org.osate.aadl2.contrib.aadlproject\n'
                      'property set AADL_Project is\n'
                      '\n'
                      '    --- The <b>Supported_Active_Thread_Handling_Protocols</b> property enumeration '
                      'type specifies the set of possible\n'
                      '    --- actions that can be taken to handle threads that are in the state of '
                      'performing computation at the time instant of\n'
                      '    --- actual mode switch.\n'
                      '    Supported_Active_Thread_Handling_Protocols: type enumeration (abort);\n'
                      '\n'
                      '    --- The <b>Supported_Connection_Patterns</b> property enumeration type specifies '
                      'the set of patterns that are supported\n'
                      '    --- to connect together a source component array and a destination component '
                      'array.<p> \n'
                      '    --- <b>One_To_One</b> represents the case when each item of the source component '
                      'array is connected to the \n'
                      '    --- corresponding item of the destination component array. This property can only '
                      'be used if the source and the\n'
                      '    --- destination component arrays of the connection have the same dimension and '
                      'same dimension size.<p>\n'
                      '    --- <b>All_To_All</b> represents the case when each item of the source component '
                      'array is connected to each item of the\n'
                      '    --- destination component array. \n'
                      '    --- A <b>Next</b> or <b>Previous</b> value indicates that elements of the '
                      'ultimate source array dimension are connected\n'
                      '    --- to the next (previous) element in the ultimate destination array dimension '
                      'without wrapping between the first and\n'
                      '    --- last.<p>\n'
                      '    --- A <b>Cyclic_Next</b> or <b>Cyclic_Previous</b> value indicates that elements '
                      'of the ultimate source array dimension\n'
                      '    --- are connected to the next (previous) element in the ultimate destination '
                      'array dimension.<p>\n'
                      '    --- <b>A One_to_All</b> value indicates that a single element of the ultimate '
                      'source has a semantic connection to each\n'
                      '    --- element in the ultimate destination.<p>  \n'
                      '    --- A <b>All_to_One</b> value indicates that each array element of the ultimate '
                      'source has a semantic connection to a\n'
                      '    --- single element in the ultimate destination.  \n'
                      '    Supported_Connection_Patterns: type enumeration (One_To_One, All_To_All, '
                      'One_To_All, All_To_One,\n'
                      '        Next, Previous, Cyclic_Next, Cyclic_Previous,\n'
                      '        Odd_To_Odd, Even_To_Even, Next_Next, Cyclic_Next_Next, Previous_Previous, '
                      'Cyclic_Previous_Previous);\n'
                      '\n'
                      '    --- The <b>Supported_Concurrency_Control_Protocols</b> property enumeration type '
                      'specifies the set of concurrency\n'
                      '    --- control protocols that are supported.    \n'
                      '    Supported_Concurrency_Control_Protocols: type enumeration (None_Specified, '
                      'Interrupt_Masking,\n'
                      '        Maximum_Priority, Priority_Inheritance, Priority_Ceiling, Spin_Lock, '
                      'Semaphore, Protected_Access);\n'
                      '\n'
                      '    --- The <b>Supported_Dispatch_Protocols</b> property enumeration type specifies '
                      'the set of thread dispatch protocols\n'
                      '    --- that are supported.<p> \n'
                      '    --- <b>Periodic</b> represents periodic dispatch of threads with deadlines. '
                      '<b>Sporadic</b> represents event-triggered\n'
                      '    --- dispatching of threads with soft deadlines. <b>Aperiodic</b> represents '
                      'event-triggered dispatch of threads with\n'
                      '    --- hard deadlines. <b>Timed</b> represents threads that are dispatched after a '
                      'given time unless they are dispatched\n'
                      '    --- by arrival of an event or event data. <b>Hybrid</b> represents threads that '
                      'are dispatched by both an event or\n'
                      '    --- event data arrival and periodically. <b>Background</b> represents threads '
                      'that are dispatched once and execute \n'
                      '    --- until completion.  The <b>Period</b> is required for <b>Periodic, Timed, and '
                      'Hybrid</b> threads.\n'
                      '    Supported_Dispatch_Protocols: type enumeration (Periodic, Sporadic, Aperiodic, '
                      'Timed, Hybrid, Background);\n'
                      '\n'
                      '    --- The <b>Supported_Queue_Processing_Protocols</b> property enumeration type '
                      'specifies the set of queue processing\n'
                      '    --- protocols that are supported.<p> \n'
                      '    --- <b>Fifo</b> represents first-in first-out processing of queues. Other '
                      'prototcols are project specific.\n'
                      '    Supported_Queue_Processing_Protocols: type enumeration (Fifo);\n'
                      '\n'
                      '    --- The <b>Supported_Hardware_Source_Languages</b> property enumeration type '
                      'specifies the set of hardware description\n'
                      '    --- languages that are supported\n'
                      '    Supported_Hardware_Source_Languages: type enumeration (VHDL);\n'
                      '\n'
                      '    --- The <b>Supported_Connection_QoS</b> property specifies the quality of '
                      'services.\n'
                      '    Supported_Connection_QoS: type enumeration (GuaranteedDelivery, OrderedDelivery, '
                      'SecureDelivery);\n'
                      '\n'
                      '    --- The <b>Supported_Scheduling_Protocols</b> property enumeration type specifies '
                      'the set of scheduling protocols\n'
                      '    --- that are supported.<p>\n'
                      '    --- Scheduling protocols that can be provided by implementers include:<p> \n'
                      '    --- <ul><li>None (single thread).</li>\n'
                      '    --- <li>Interrupt-driven (handling of interrupt service routines (ISR)).</li>\n'
                      '    --- <li>For periodic task sets: fixed timeline, cooperative (cyclic executive), '
                      'deadline monotonic, least laxity.</li></ul>\n'
                      '    --- <ul><li>For hybrid task set:</li> \n'
                      '    --- <li>Fixed priority server based on Rate Monotonic Scheduling (RMS) (polling '
                      'server, deferrable server, sporadic\n'
                      '    --- server, slack stealer).</li> \n'
                      '    --- <li>Dynamic priority server based on Earliest Deadline First (EDF) (dynamic '
                      'polling server, dynamic deferrable\n'
                      '    --- server,</li> \n'
                      '    --- <li>dynamic sporadic server, total bandwidth server, constant bandwidth '
                      'server).</li></ul>\n'
                      '    --- Scheduling protocols have a policy for scheduling periodic threads, for '
                      'aperiodic/sporadic threads, and for\n'
                      '    --- background threads.  In the case of RMS, the periodic thread policy is '
                      'priority assignment according to decreasing\n'
                      '    --- rate, aperiodic and sporadic threads according to their minimum inter-arrival '
                      'time, and background as <b>FIFO</b>.\n'
                      '    --- Others have similar characteristics.  \n'
                      '    Supported_Scheduling_Protocols: type enumeration (Static, Round_Robin_Protocol,\n'
                      '        POSIX_1003_HIGHEST_PRIORITY_FIRST_PROTOCOL, FixedTimeline, Cooperative, RMS, '
                      'DMS, EDF, SporadicServer,\n'
                      '        SlackServer, ARINC653);\n'
                      '\n'
                      '    --- The <b>Supported_Source_Languages</b> property enumeration type specifies the '
                      'set of software source languages that\n'
                      '    --- are supported.\n'
                      '    Supported_Source_Languages: type enumeration (Ada95, Ada2005, C, Java, '
                      'Simulink_6_5, SCADE);\n'
                      '\n'
                      '    --- The <b>Supported_Distribution</b> property enumeration type specifies the set '
                      'of distribution functions that are\n'
                      '    --- supported.\n'
                      '    Supported_Distributions: type enumeration (Fixed, Poisson);\n'
                      '\n'
                      '    ---The <b>Supported_Classifier_Substitutions</b> property enumeration type '
                      'specifies the set of classifier \n'
                      '    --- substitutions that are supported for prototypes and refinement declarations.  '
                      'Three kinds of substitutions are\n'
                      '    --- defined as part of the standard.  A candidate for additional substitution '
                      'rules signature matching with name\n'
                      '    --- mapping.\n'
                      '    Supported_Classifier_Substitutions: type enumeration (Classifier_Match, '
                      'Type_Extension, Signature_Match);\n'
                      '\n'
                      '    --- The following four property constants support values which are lists of pairs '
                      'of classifiers.  An example value could be the following:\n'
                      '    --- ((classifier (pkg::a), classifier (pkg::b)), (classifier (pkg::c), classifier '
                      '(pkg::d)), (classifier (pkg::e), classifier (pkg::f)))\n'
                      '    --- The size of each inner list must be exactly two.  There are no restrictions '
                      'on the size of the outer list.\n'
                      '    Supported_Classifier_Equivalence_Matches: constant list of list of classifier '
                      '(data, bus, subprogram,\n'
                      '        subprogram group, feature group) => ();\n'
                      '\n'
                      '    Supported_Classifier_Subset_Matches: constant list of list of classifier (data, '
                      'bus, subprogram,\n'
                      '        subprogram group) => ();\n'
                      '\n'
                      '    Supported_Type_Conversions: constant list of list of classifier (data, bus, '
                      'subprogram, subprogram group) => ();\n'
                      '\n'
                      '    Supported_Classifier_Complement_Matches: constant list of list of classifier '
                      '(feature group) => ();\n'
                      '\n'
                      '    --- The property constant <b>Max_Aadlinteger</b> specifies the largest machine '
                      'representable integer value that may be\n'
                      '    --- used as the maximum value in property associations. This property does not '
                      'specify the maximum integer\n'
                      '    --- representation on a target processor.\n'
                      '    Max_Aadlinteger: constant aadlinteger => 2#1#e32;\n'
                      '\n'
                      '    --- The property constant <b>Max_Target_Integer</b> specifies the largest machine '
                      'representable integer value on a\n'
                      '    --- target processor. \n'
                      '    Max_Target_Integer: constant aadlinteger => 2#1#e32;\n'
                      '\n'
                      '    --- The property constant <b>Max_Base_Address</b> specifies the maximum value '
                      'that can be declared in for the\n'
                      '    --- <b>Base_Address</b> property.\n'
                      '    Max_Base_Address: constant aadlinteger => 2#1#e32;\n'
                      '\n'
                      '    --- The property constant <b>Max_Memory_Size</b> specifies the maximum memory '
                      'size that can be declared in for the\n'
                      '    --- <b>Size</b> property, expressed in the specified unit of <b>Size</b>.   \n'
                      '    Max_Memory_Size: constant Size => 2#1#e32 Bytes;\n'
                      '\n'
                      '    --- The property constant <b>Max_Queue_Size</b> specifies the maximum value that '
                      'can be declared in for the\n'
                      '    --- <b>Queue_Size</b> property.\n'
                      '    Max_Queue_Size: constant aadlinteger => 512;\n'
                      '\n'
                      '    --- The property constant <b>Max_Thread_Limit</b> specifies the maximum value '
                      'that can be declared in for the\n'
                      '    --- <b>Thread_Limit</b> property.\n'
                      '    Max_Thread_Limit: constant aadlinteger => 32;\n'
                      '\n'
                      '    --- The property constant <b>Max_Time</b> specifies the maximum value that can be '
                      'declared in for the <b>Time</b>\n'
                      '    --- property, expressed in the specified  unit of <b>Time</b>.\n'
                      '    Max_Time: constant Time => 1000hr;\n'
                      '\n'
                      '    --- The property constant <b>Max_Urgency</b> specifies the maximum value that can '
                      'be declared in for the <b>Urgency</b>\n'
                      '    --- property.\n'
                      '    Max_Urgency: constant aadlinteger => 12;\n'
                      '\n'
                      '    --- The property constant <b>Max_Word_Space</b> specifies the maximum value that '
                      'can be declared in for the\n'
                      '    --- Word_Space property\n'
                      '    Max_Word_Space: constant aadlinteger => 64;\n'
                      '\n'
                      '    --- The <b>Time</b> property type specifies a property type for time that is '
                      'expressed as numbers with predefined time\n'
                      '    --- units.\n'
                      '    Time: type aadlinteger units Time_Units; --0 ps .. Max_Time units Time_Units;\n'
                      '\n'
                      '    --- The <b>Time_Range property</b> type specifies a property type for a closed '
                      'range of time, i.e., a time span\n'
                      '    --- including the lower and upper bound.<p> \n'
                      '    --- The property type is Time.  The standard units are ps (picoseconds), ns '
                      '(nanoseconds), us (microseconds), ms\n'
                      '    --- (milliseconds), sec (seconds), min (minutes) and hr (hours).\n'
                      '    Time_Range: type range of Time;\n'
                      '\n'
                      '    --- Memory size as integers with predefined size units.\n'
                      '    Size: type aadlinteger units Size_Units; -- 0 Bytes .. Max_Memory_Size units '
                      'Size_Units;\n'
                      '\n'
                      '    --- The <b>Size_Range</b> property specifies a closed range of memory size '
                      'values, i.e., a memory size range including\n'
                      '    --- the lower and upper bound.\n'
                      '    Size_Range: type range of Size;\n'
                      '\n'
                      '    --- The type <b>Size_Units</b> defines a measurement of size that is available '
                      'for use in other property definitions.\n'
                      '    --- Users may append to this type.<p>\n'
                      '    --- Note: conversion factor of 1000 consistent with ISO/SI.<p>\n'
                      '    --- Note: B, KB, etc. in AADL 2004 have been replaced by Byte, Kbyte etc. in AADL '
                      'V2. Since AADL is case insensitive,\n'
                      '    --- Kb could have been interpreted a K bits rather than K bytes.<p>\n'
                      '    --- Note: There is a proposal to add the binary units as defined by IEC, i.e., '
                      'KiByte => Bytes * 1024, MiByte => KiByte\n'
                      '    --- * 1024, etc.\n'
                      '    Size_Units: type units (bits, Bytes => bits * 8,\n'
                      '        KByte => Bytes * 1000, MByte => KByte * 1000, GByte => MByte * 1000, TByte => '
                      'GByte * 1000,\n'
                      '        KiByte => Bytes * 1024, MiByte => KiByte * 1024, GiByte => MiByte * 1024, '
                      'TiByte => GiByte * 1024);\n'
                      '\n'
                      '    --- The type <b>Time_Units</b> defines a measurement of time that is available '
                      'for use in other property definitions.\n'
                      '    --- Users may append to this type\n'
                      '    Time_Units: type units (ps, ns => ps * 1000, us => ns * 1000, ms => us * 1000, '
                      'sec => ms * 1000, min => sec * 60,\n'
                      '        hr => min * 60);\n'
                      '\n'
                      '    --- The <b>Data_Rate_Units</b> property type units for the rate of data per time '
                      'unit as a property type.  The\n'
                      '    --- predeclared unit literals are expressed in terms of seconds as time '
                      'unit.<p>  \n'
                      '    --- Note: Conversion factor of 1000 consistent with ISO.\n'
                      '    Data_Rate_Units: type units (bitsps,\n'
                      '        kbps => bitsps * 1000, Mbps => Kbps * 1000, Gbps => Mbps * 1000,\n'
                      '        Bytesps => bitsps * 8, KBytesps => Bytesps * 1000, MBytesps => KBytesps * '
                      '1000, GBytesps => MBytesps * 1000);\n'
                      '\n'
                      '    --- The type <b>Processor_Speed_Units</b> defines a measurement of the speed at '
                      'which a processor operates. \n'
                      '    Processor_Speed_Units: type units (KIPS, MIPS => KIPS * 1000, GIPS => MIPS * '
                      '1000);\n'
                      '\n'
                      '\t--- <b>DEPRECATED</b><p>\n'
                      '\t--- Replaced by Max_Memory_Size\n'
                      '    Max_Byte_Count: constant aadlinteger => 2#1#e32;\n'
                      '\n'
                      '\t--- <b>DEPRECATED</b><p>\n'
                      '\t--- Replaced by Data_Rate_Units\n'
                      '\tData_Volume_Units: type\tunits (bitsps, Bytesps => bitsps * 8, KBytesps => Bytesps '
                      '* 1000, MBytesps => KBytesps * 1000, GBytesps => MBytesps * 1000);\n'
                      '\n'
                      '\t--- <b>DEPRECATED</b><p>\n'
                      '\tMax_Volume: constant Data_Volume => 1000 GBytesps;\n'
                      '\n'
                      '\t--- <b>DEPRECATED</b><p>\n'
                      '\t--- Replaced by Data_Rate\n'
                      '\tData_Volume: type aadlinteger 0 bitsps .. Max_Volume units Data_Volume_Units;\n'
                      '\n'
                      'end AADL_Project;\n',
 'Communication_Properties.aadl': '--AADLv2.3\n'
                                  '--SAE Aerospace Standard AS5506D\n'
                                  '--Appendix A: Predeclared Property Sets\n'
                                  '-- @codegen-package org.osate.aadl2.contrib.communication\n'
                                  'property set Communication_Properties is\n'
                                  '\n'
                                  '    --- The <b>Fan_Out_Policy</b> property specifies how the output is '
                                  'distributed to multiple recipients of a port with\n'
                                  '    --- multiple outgoing connections.  <b>Broadcast</b> sends to all '
                                  'recipients, <b>RoundRobin</b> to one recipient at a\n'
                                  '    --- time in order, <b>Selective</b> sends to one recipient based on '
                                  'data content, and <b>OnDemand</b> to the next \n'
                                  '    --- recipient waiting on a port for dispatch.<p>\n'
                                  '    --- Note that <b>Broadcast, RoundRobin, and Selective</b> pass on '
                                  'data and events without queuing it, while\n'
                                  '    --- <b>OnDemand</b> requires a queue that is serviced by the '
                                  'recipients. \n'
                                  '    Fan_Out_Policy: enumeration (Broadcast, RoundRobin, Selective, '
                                  'OnDemand) applies to (feature);\n'
                                  '\n'
                                  '    --- The <b>Connection_Pattern</b> property specifies how an '
                                  'individual connection between arrays of ports looks like.\n'
                                  '    --- If the property is not set the <b>One_to_One pattern</b> '
                                  'applies.  The outer list has an element for each array\n'
                                  '    --- dimension. The inner list specifies one or more patterns for that '
                                  'dimension\n'
                                  '    Connection_Pattern: list of list of Supported_Connection_Patterns '
                                  'applies to (connection, flow specification);\n'
                                  '\n'
                                  '    --- The <b>Connection_Set</b> property specifies a list of specific '
                                  'source element and destination element of a\n'
                                  '    --- semantic connection by their array indices.         \n'
                                  '    Connection_Set: list of Connection_Pair applies to (connection, flow '
                                  'specification);\n'
                                  '\n'
                                  '    Connection_Pair: type record (\n'
                                  '        src: list of aadlinteger;\n'
                                  '        dst: list of aadlinteger;);\n'
                                  '\n'
                                  '    --- The <b>Overflow_Handling_Protocol</b> property specifies the '
                                  'runtime behavior of a thread when an event arrives and\n'
                                  '    --- the queue is full. <b>DropOldest</b> removes the oldest event '
                                  'from the queue and adds the new arrival.\n'
                                  '    --- <b>DropNewest</b> ignores the newly arrived event. <b>Error</b> '
                                  'causes the thread�s error recovery to be invoked. \n'
                                  '    --- The default value is <b>DropOldest</b>.\n'
                                  '    Overflow_Handling_Protocol: enumeration (DropOldest, DropNewest, '
                                  'Error) => DropOldest applies to (event port,\n'
                                  '        event data port, subprogram access);\n'
                                  '\n'
                                  '    --- The <b>Queue_Processing_Protocol</b> property specifies the '
                                  'protocol for processing elements in the queue.\n'
                                  '    Queue_Processing_Protocol: Supported_Queue_Processing_Protocols => '
                                  'FIFO applies to (event port, event data port,\n'
                                  '        subprogram access);\n'
                                  '\n'
                                  '    --- The <b>Queue_Size</b> property specifies the size of the queue '
                                  'for an event, event data port, of a subprogram\n'
                                  '    --- access feature,.  In the case of a subprogram access it '
                                  'represents the queue for remote subprogram calls.\n'
                                  '    Queue_Size: aadlinteger 0 .. Max_Queue_Size => 1 applies to (event '
                                  'port, event data port, subprogram access);\n'
                                  '\n'
                                  '    --- The <b>Required_Connection</b> property specifies whether the '
                                  'port or subprogram requires a connection.  If the\n'
                                  '    --- value of this property is false, then it is assumed that the '
                                  'component can function without this port or \n'
                                  '    --- subprogram access feature being connected.<p>\n'
                                  '    --- OSATE defaults this property to false, which intentionally '
                                  'deviates from the AADL standard default of true.\n'
                                  '    Required_Connection: aadlboolean => false applies to (feature);\n'
                                  '\n'
                                  '    --- The <b>Timing</b> property specifies the timing of port '
                                  'connections.  By default the interaction is <b>sampled</b>,\n'
                                  '    --- i.e., the receiving component samples at dispatch or during '
                                  'execution.\n'
                                  '    Timing: enumeration (sampled, immediate, delayed) => sampled applies '
                                  'to (port connection);\n'
                                  '\n'
                                  '    --- The <b>Transmission_Type</b> property specifies whether the '
                                  'transmission across a data port connection is initiated\n'
                                  '    --- by the sender (<b>push</b>) or by the receiver (<b>pull</b>).  By '
                                  'default the transmission is initiated by the sender.\n'
                                  '    --- A pull transmission type results in data being transmitted at the '
                                  'rate of the receiver.  In the case of event data\n'
                                  '    --- port or event ports,  a <b>pull</b> transmission type results in '
                                  'events or event data queued with the sender to be\n'
                                  '    --- transmitted upon receiver request.<p>\n'
                                  '    --- When associated with a connection the property represents the '
                                  'transmission type the connection expects.  When\n'
                                  '    --- associated with a port the property represents the transmission '
                                  'type expected by the port.  When associated with a\n'
                                  '    --- bus or virtual bus the property represents the transmission type '
                                  'that is provided by the bus or protocol.\n'
                                  '    Transmission_Type: enumeration (push, pull) applies to (feature, '
                                  'connection, bus, virtual bus);\n'
                                  '\n'
                                  '    --- The <b>Input_Rate</b> property specifies the number of inputs per '
                                  'dispatch or per second of data, events, event\n'
                                  '    --- data, or subprogram calls. If no <b>Input_Rate</b> is specified '
                                  'the default is one input per thread dispatch.<p>\n'
                                  '    --- If no distribution function is specified it is assumed to be '
                                  '<b>Fixed</b>.\n'
                                  '    Input_Rate: Rate_Spec => [Value_Range => 1.0 .. 1.0; Rate_Unit => '
                                  'PerDispatch;\n'
                                  '        Rate_Distribution => Fixed;] applies to (feature);\n'
                                  '\n'
                                  '    --- The <b>Input_Time</b> property specifies the amount of execution '
                                  'time that can pass after dispatch before the input\n'
                                  '    --- is frozen on a given port.  The property value is a pair of '
                                  '<b>Time</b> a time range <b>Offset</b>. The default \n'
                                  '    --- input time is <b>Dispatch</b> with zero  <b>Offset</b>. A typical '
                                  'property value is a time offset in terms of\n'
                                  '    --- <b>Start</b>.\n'
                                  '    Input_Time: list of IO_Time_Spec => ([Time => Dispatch; Offset => 0ns '
                                  '.. 0ns;]) applies to (feature);\n'
                                  '\n'
                                  '    ---The <b>IO_Time_Spec</b> property specifies the amount of execution '
                                  'time <b>Offset</b> relative to a Time at which\n'
                                  '    --- input or output occurs. The value consists of a reference point '
                                  'and time range pair.\n'
                                  '    IO_Time_Spec: type record (\n'
                                  '        Offset: Time_Range;\n'
                                  '        Time: IO_Reference_Time;\n'
                                  '    );\n'
                                  '\n'
                                  '    --- The <b>IO_Reference_Time</b> type specifies the time reference '
                                  'point to be used for specifying when input or\n'
                                  '    --- output is available.  The reference points are dispatch time '
                                  '(typically with zero time offset), start time (zero \n'
                                  '    --- or more time), completion time (amount of execution time until '
                                  'completion), and deadline (typically with zero time\n'
                                  '    --- offset). <b>NoIO</b> indicates that no I/O occurs. Dynamic '
                                  'indicates that, if I/O occurs, the time can be anytime\n'
                                  '    --- during execution without specifying a specific offset relative to '
                                  'start or completion time.\n'
                                  '    IO_Reference_Time: type enumeration (Dispatch, Start, Completion, '
                                  'Deadline, NoIO, Dynamic);\n'
                                  '\n'
                                  '    --- The <b>Output_Rate</b> property specifies the number of outputs '
                                  'per dispatch or per second of data, events, event\n'
                                  '    --- data, or initiations of subprogram calls.  The default is one '
                                  'output per thread dispatch and the default\n'
                                  '    ---  distribution is <b>Fixed</b>.\n'
                                  '    Output_Rate: Rate_Spec => [Value_Range => 1.0 .. 1.0; Rate_Unit => '
                                  'PerDispatch;\n'
                                  '        Rate_Distribution => Fixed;] applies to (feature);\n'
                                  '\n'
                                  '    --- The <b>Output_Time</b> property specifies the amount of execution '
                                  'time until completion at which output becomes\n'
                                  '    --- available. The property value is a pair of <b>Time</b> and '
                                  '<b>Offset</b>. The default <b>Output_Time</b> is\n'
                                  '    --- <b>Completion</b> with zero  <b>Offset</b>. For data ports with a '
                                  'delayed connection the default output time is\n'
                                  '    --- <b>Deadline</b>.\n'
                                  '    Output_Time: list of IO_Time_Spec => ([Time => Completion; Offset => '
                                  '0ns .. 0ns;]) applies to (feature);\n'
                                  '\n'
                                  '    --- The <b>Rate_Spec</b> type specifies the number of input or output '
                                  'occurrences per <b>Rate_Unit</b>, i.e., per\n'
                                  '    --- dispatch or per second. The default <b>Rate_Distribution</b> is '
                                  '<b>Fixed</b>.  \n'
                                  '    Rate_Spec: type record (\n'
                                  '        Value_Range: range of aadlreal;\n'
                                  '        Rate_Unit: enumeration (PerSecond, PerDispatch);\n'
                                  '        Rate_Distribution: Supported_Distributions;\n'
                                  '    );\n'
                                  '\n'
                                  '    --- The <b>Subprogram_Call_Rate</b> property specifies the number of '
                                  'subprogram calls per dispatch or per second.  The\n'
                                  '    --- default is one call per thread dispatch and the default '
                                  'distribution is <b>Fixed</b>.   \n'
                                  '    Subprogram_Call_Rate: Rate_Spec => [Value_Range => 1.0 .. 1.0; '
                                  'Rate_Unit => PerDispatch;\n'
                                  '        Rate_Distribution => Fixed;] applies to (subprogram access);\n'
                                  '\n'
                                  '    --- The <b>Transmission_Time</b> property specifies the parameters '
                                  'for a linear model for the time interval between the\n'
                                  '    --- start and end of a transmission of a sequence of N bytes onto a '
                                  'bus.  The transmission time is the time between the\n'
                                  '    --- transmission of the first bit of the message onto the bus and the '
                                  'transmission of the last bit of the message onto\n'
                                  '    --- the bus.  This time excludes arbitration, queuing, or any other '
                                  'times that are a function of how bus contention and\n'
                                  '    --- scheduling are performed for a bus.<p> \n'
                                  '    --- The associated expressions must evaluate to a record of two '
                                  'ranges of nonnegative numbers.<p>\n'
                                  '    --- The time required to transmit a message of N Bytes over the bus '
                                  'is <b>Fixed + N * PerByte</b>, where <b>Fixed</b> \n'
                                  '    --- is any number that falls in the range of the <b>Fixed</b> record '
                                  'field  and <b>PerByte</b> is any number that falls\n'
                                  '    --- in the range of the <b>PerByte</b> record field.  \n'
                                  '    Transmission_Time: record (\n'
                                  '        Fixed: Time_Range;\n'
                                  '        PerByte: Time_Range;) applies to (bus, system, device, processor, '
                                  'memory, virtual bus, virtual processor);\n'
                                  '\n'
                                  '    --- The <b>Actual_Latency</b> property specifies the actual latency '
                                  'as determined by the implementation of the \n'
                                  '    --- end-to-end flow through semantic connections.  Its numeric value '
                                  'must be positive.<p>    \n'
                                  '    Actual_Latency: Time_Range applies to (flow, connection, virtual bus, '
                                  'bus, processor, virtual processor, device,\n'
                                  '        system, feature, memory);\n'
                                  '\n'
                                  '    --- The <b>Latency</b> property specifies the minimum and maximum '
                                  'amount of elapsed time allowed between the time the\n'
                                  '    --- data or events enter the connection or flow and the time it '
                                  'exits.  Its numeric value must be positive.<p>\n'
                                  '    Latency: Time_Range applies to (flow, connection, virtual bus, bus, '
                                  'processor, virtual processor, device, system,\n'
                                  '        feature, memory);\n'
                                  '\n'
                                  '    --- The <b>Data_Rate</b> property type specifies a property for the '
                                  'rate of data per time unit.  The numeric  \n'
                                  '    --- value of the property must be positive.\n'
                                  '    Data_Rate: aadlinteger units Data_Rate_Units applies to (feature, '
                                  'connection, bus, virtual bus, system, processor,\n'
                                  '        virtual processor, memory, device);\n'
                                  '\n'
                                  'end Communication_Properties;\n',
 'Deployment_Properties.aadl': '--AADL2\n'
                               '--SAE Aerospace Standard AS5506D\n'
                               '--Appendix A: Predeclared Property Sets\n'
                               '-- @codegen-package org.osate.aadl2.contrib.deployment\n'
                               'property set Deployment_Properties is\n'
                               '\n'
                               '    --- The <b>Allowed_Processor_Binding_Class</b> property specifies a set '
                               'of virtual processor, processor and system \n'
                               '    --- classifiers. These component classifiers constrain the set of '
                               'candidate virtual processors and processors for\n'
                               '    --- binding to the subset that satisfies the component classifier.<p> \n'
                               '    --- The value may be inherited from the containing component.<p>\n'
                               '    --- If this property has no associated value, then all processors '
                               'specified in the <b>Allowed_Processor_Binding</b> are\n'
                               '    --- acceptable candidates.\n'
                               '    Allowed_Processor_Binding_Class: inherit list of classifier (processor, '
                               'virtual processor, system, device,\n'
                               '        abstract) applies to (thread, thread group, process, system, virtual '
                               'processor, device);\n'
                               '\n'
                               '    --- The <b>Allowed_Processor_Binding</b> property specifies the set of '
                               'virtual processors and processors that are\n'
                               '    --- available for binding.  The set is specified by a list of virtual '
                               'processor, processor and system component names.\n'
                               '    --- System names represent the processors contained in them.<p>\n'
                               '    --- If the property is specified for a thread, the thread can be bound '
                               'to any of the specified set of virtual \n'
                               '    --- processors or processors for execution. If the property is specified '
                               'for a thread group, process, or system, then \n'
                               '    --- it applies to all contained threads, i.e., the contained threads '
                               'inherit the property association unless \n'
                               '    --- overridden. If this property is specified for a device, then the '
                               'thread associated with the device driver code can\n'
                               '    --- be bound to one of the set of processors for execution. The '
                               '<b>Allowed_Processor_Binding</b> property may specify\n'
                               '    --- a single processor, thus specifying the exact processor binding. '
                               '<p> \n'
                               '    --- The allowed binding may be further constrained by the processor '
                               'classifier reference specified in the \n'
                               '    --- <b>Allowed_Processor_Binding_Class</b> property. <p>\n'
                               '    --- If this property has no associated value, then all processors '
                               'declared in n AADL specification are\n'
                               '    --- acceptable candidates.\n'
                               '    Allowed_Processor_Binding: inherit list of reference (processor, virtual '
                               'processor, system, device,\n'
                               '        abstract) applies to (thread, thread group, process, system, virtual '
                               'processor, device);\n'
                               '\n'
                               '    --- A thread is bound to the processor specified by the '
                               '<b>Actual_Processor_Binding</b> property.  The process of \n'
                               '    --- binding threads to processors determines the value of this '
                               'property.  If there is more than one processor listed, \n'
                               '    --- a scheduler will dynamically assign the thread to one at a time.  '
                               'This allows modeling of multi-core processors \n'
                               '    --- without explicit binding to one of the cores.<p>\n'
                               '    --- If a device is bound to a processor this indicates the binding of '
                               'the device driver software. \n'
                               '    --- A virtual processor may be bound to a processor. This indicates that '
                               'the virtual processor executes on the \n'
                               '    --- processor it is bound to.<p>\n'
                               '    --- Threads, devices, and virtual processors can be bound to virtual '
                               'processors, which in turn are bound to virtual\n'
                               '    --- processors or processors.\n'
                               '    Actual_Processor_Binding: inherit list of reference (processor, virtual '
                               'processor, system, device,\n'
                               '        abstract) applies to (thread, thread group, process, system, virtual '
                               'processor, device);\n'
                               '\n'
                               '    --- The <b>Allowed_Memory_Binding_Class</b> property specifies a set of '
                               'memory, device, and system classifiers.  These\n'
                               '    --- classifiers constrain the set of memory components in the '
                               '<b>Allowed_Memory_Binding</b> property to the subset that\n'
                               '    --- satisfies the component classifier.<p> \n'
                               '    --- The value of the <b>Allowed_Memory_Binding</b> property may be '
                               'inherited from the component that contains the \n'
                               '    --- component or feature.<p>\n'
                               '    --- If this property has no associated value, then all memory components '
                               'specified in the <b>Allowed_Memory_Binding</b>\n'
                               '    --- are acceptable candidates.\n'
                               '    Allowed_Memory_Binding_Class: inherit list of classifier (memory, '
                               'system, processor, virtual processor,\n'
                               '        abstract) applies to (thread, thread group, process, system, device, '
                               'data, data port, event data port,\n'
                               '        subprogram, subprogram group, processor, virtual processor);\n'
                               '\n'
                               '    --- Code and data produced from source text can be bound to the set of '
                               'memory components that is specified by \n'
                               '    --- the <b>Allowed_Memory_Binding</b> property.  The set is specified by '
                               'a list of memory and system component\n'
                               '    --- names.  System names represent the memories contained in them.  The '
                               '<b>Allowed_Memory_Binding</b> property\n'
                               '    --- may specify a single memory, thus specifying the exact memory '
                               'binding.<p>  \n'
                               '    --- The allowed binding may be further constrained by the memory '
                               'classifier specified in the\n'
                               '    --- <b>Allowed_Memory_Binding_Class</b>.<p> \n'
                               '    --- The value of the <b>Allowed_Memory_Binding</b> property may be '
                               'inherited from the component that contains the \n'
                               '    --- component or feature.<p>\n'
                               '    --- If this property has no associated value, then all memory components '
                               'declared in an AADL specification are \n'
                               '    --- acceptable candidates.\n'
                               '    Allowed_Memory_Binding: inherit list of reference (memory, system, '
                               'processor, virtual processor,\n'
                               '        abstract) applies to (memory, thread, thread group, process, system, '
                               'device, data, data port, event data port,\n'
                               '        subprogram, subprogram group, processor, virtual processor);\n'
                               '\n'
                               '    --- Code and data from source text is bound to the memory specified by '
                               'the <b>Actual_Memory_Binding</b> property.  The\n'
                               '    --- property can hold a list of values to reflect the possibility of '
                               'code and data being bound to different memory \n'
                               '    --- components, for example.\n'
                               '    Actual_Memory_Binding: inherit list of reference (memory, system, '
                               'processor, virtual processor,\n'
                               '        abstract) applies to (memory, thread, thread group, process, system, '
                               'processor, virtual processor, device, data,\n'
                               '        data port, event data port, subprogram, subprogram group);\n'
                               '\n'
                               '    --- The <b>Allowed_Connection_Binding_Class</b> property specifies a set '
                               'of execution platform classifiers to constrain\n'
                               '    --- the binding of connections and virtual buses.  The named component '
                               'classifiers must belong to a processor, virtual\n'
                               '    --- processor, bus, virtual bus, device, or memory category, i.e., any '
                               'execution platform component that supports \n'
                               '    --- communication between threads.  When specified for a feature it '
                               'indicates a binding constraint for all connections\n'
                               '    --- through that feature, e.g., any protocol assumptions a component '
                               'makes about its communication through the port.        \n'
                               '    Allowed_Connection_Binding_Class: inherit list of classifier (processor, '
                               'virtual processor, bus, virtual bus,\n'
                               '        device, memory, system, abstract) applies to (feature, connection, '
                               'thread, thread group, process, system,\n'
                               '        virtual bus);\n'
                               '\n'
                               '    --- The <b>Allowed_Connection_Binding</b> property specifies a set of '
                               'execution platform components to constrain the\n'
                               '    --- binding of connections and virtual buses.  The named components must '
                               'belong to a processor, virtual processor,\n'
                               '    --- bus, virtual bus, device, or memory category. When specified for a '
                               'feature such as a port it indicates a binding\n'
                               '    --- constraint for all connections through that feature, e.g., any '
                               'protocol assumptions a component makes about its\n'
                               '    --- communication through the port.\n'
                               '    Allowed_Connection_Binding: inherit list of reference (processor, '
                               'virtual processor, bus, virtual bus, device,\n'
                               '        memory, system, abstract) applies to (feature, connection, thread, '
                               'thread group, process, system, virtual bus);\n'
                               '\n'
                               '    --- Connections and virtual buses are bound to the bus, virtual bus, '
                               'processor, virtual processor, device, and memory\n'
                               '    --- specified by the <b>Actual_Connection_Binding</b> property.  The '
                               'entries in the list represent the flow sequence\n'
                               '    --- of the connection through the execution platform.\n'
                               '    Actual_Connection_Binding: inherit list of reference (processor, virtual '
                               'processor, bus, virtual bus, device,\n'
                               '        memory, system, abstract) applies to (feature, connection, thread, '
                               'thread group, process, system, virtual bus);\n'
                               '\n'
                               '    --- The <b>Actual_Function_Binding</b> property allows users to define '
                               'mappings from functional architectures to system architectures.\n'
                               '    Actual_Function_Binding: inherit list of reference (processor, virtual '
                               'processor, bus, virtual bus, device, memory,\n'
                               '        system, process, thread, feature, abstract) applies to (subprogram, '
                               'thread, thread group, process, system,\n'
                               '        abstract, feature);\n'
                               '\n'
                               '    --- A subprogram call can be bound to any member of the set of '
                               'subprograms specified by the\n'
                               '    --- <b>Allowed_Subprogram_Call</b> property. These may be remote '
                               'subprograms, i.e., subprogram instances in other\n'
                               '    --- threads, or local subprogram instances.  In the latter case the '
                               'property identifies a specific code instance. If no\n'
                               '    --- value is specified, then subprogram call must be a local call.\n'
                               '    Allowed_Subprogram_Call: list of reference (subprogram) applies to '
                               '(subprogram access);\n'
                               '\n'
                               '    -- The <b>Actual_Subprogram_Call</b> property specifies the subprogram '
                               'instance whose code is servicing the subprogram\n'
                               '    --- call. These may be remote subprograms, i.e., subprogram instances in '
                               'other threads, or local subprogram instances.\n'
                               '    --- In the latter case the property identifies a specific local code '
                               'instance, i.e., it can model sharing of \n'
                               '    --- subprogram. If no value is specified, the subprogram call is a local '
                               'call.\n'
                               '    Actual_Subprogram_Call: reference (subprogram) applies to (subprogram '
                               'access);\n'
                               '\n'
                               '    --- Remote subprogram calls can be bound to the physical connection of '
                               'an execution platform that is specified by the\n'
                               '    --- <b>Allowed_Subprogram_Call_Binding</b> property. If no value is '
                               'specified, then subprogram call may be a local call\n'
                               '    --- or the binding may be inferred from the bindings of the caller and '
                               'callee.\n'
                               '    Allowed_Subprogram_Call_Binding: list of reference (bus, processor, '
                               'device) applies to (subprogram, thread,\n'
                               '        thread group, process, system);\n'
                               '\n'
                               '    --- The <b>Actual_Subprogram_Call_Binding</b> property specifies the '
                               'bus, processor, device, or memory to which a \n'
                               '    --- remote subprogram call is bound. If no value is specified, the '
                               'subprogram call is a local call or the binding can\n'
                               '    --- be inferred from the binding of the caller and callee\n'
                               '    Actual_Subprogram_Call_Binding: list of reference (bus, processor, '
                               'memory, device) applies to (subprogram);\n'
                               '\n'
                               '    --- The <b>Provided_Virtual_Bus_Class</b> property specifies the set of '
                               'virtual bus classifiers (protocols) supported\n'
                               '    --- by a  bus, virtual bus, virtual processor, device, or processor.  '
                               'The property indicates that a component with a\n'
                               '    --- binding requirement for a virtual bus classifier can be bound to a '
                               'component whose\n'
                               '    --- <b>Provided_Virtual_Bus_Class</b> property value includes the '
                               'desired virtual bus classifier.  Note that the\n'
                               '    --- component with this property is not required to have virtual bus '
                               'subcomponents.\n'
                               '    Provided_Virtual_Bus_Class: inherit list of classifier (virtual bus) '
                               'applies to (bus, virtual bus, processor,\n'
                               '        virtual processor, device, memory, system);\n'
                               '\n'
                               '    --- The <b>Required_Virtual_Bus_Class</b> property specifies the set of '
                               'virtual bus classifiers (protocols) that this\n'
                               '    --- connection or virtual bus needs to be bound to, i.e., that it '
                               'requires to be bound to one instance of each of the\n'
                               '    --- specified classifiers.  This property complements the '
                               '<b>Allowed_Connection_Binding_Class</b> property, which\n'
                               '    --- specifies that the connection binding must be to components of one '
                               'of the specified classifiers. \n'
                               '    Required_Virtual_Bus_Class: inherit list of classifier (virtual bus) '
                               'applies to (virtual bus, connection, port,\n'
                               '        thread, thread group, process, system, device);\n'
                               '\n'
                               '    --- The <b>Provided_Connection_Quality_Of_Service</b> property specifies '
                               'the quality of service provided by a protocol,\n'
                               '    --- i.e., a virtual bus, bus, virtual processor, or processor supporting '
                               'protocols, for its transmission.\n'
                               '    Provided_Connection_Quality_Of_Service: inherit list of '
                               'Supported_Connection_QoS applies to (bus, virtual bus,\n'
                               '        processor, virtual processor, system, device, memory);\n'
                               '\n'
                               '    --- The <b>Required_Connection_Quality_Of_Service</b>, property '
                               'specifies that a connection or virtual bus expects a\n'
                               '    --- certain quality of service from the protocol that is used for its '
                               'transmission.\n'
                               '    Required_Connection_Quality_Of_Service: inherit list of '
                               'Supported_Connection_QoS applies to (port, connection,\n'
                               '        virtual bus, thread, thread group, process, system, device);\n'
                               '\n'
                               '    --- The <b>Not_Collocated</b> property specifies that hardware resources '
                               'used by several software components must be\n'
                               '    --- distinct.  The components referenced by <b>Target</b> must not be '
                               'bound to the same hardware of the type specified\n'
                               '    --- in the <b>Location</b> field.  If the <b>Location</b> is a system '
                               'component, then they may not be collocated to any\n'
                               '    --- component contained in the system component.\n'
                               '    Not_Collocated: record (\n'
                               '        Targets: list of reference (data, thread, process, system, '
                               'connection);\n'
                               '        Location: classifier (processor, memory, bus, system);) applies to '
                               '(process, system);\n'
                               '\n'
                               '    --- The <b>Collocated</b> property specifies that several software '
                               'components must be bound to the same hardware.  The\n'
                               '    --- components referenced by <b>Target</b> must be bound to the same '
                               'hardware of the type specified in the \n'
                               '    --- <b>Location</b> field.  If the <b>Location</b> is a system '
                               'component, then they must be collocated on any component\n'
                               '    --- contained in the system component.\n'
                               '    Collocated: record (\n'
                               '        Targets: list of reference (data, thread, process, system, '
                               'connection);\n'
                               '        Location: classifier (processor, memory, bus, system);) applies to '
                               '(process, system);\n'
                               '\n'
                               '    --- The <b>Allowed_Connection_Type</b> property specifies the categories '
                               'of connections a bus supports.  That is, a\n'
                               '    --- connection may only be legally bound to a bus if the bus supports '
                               'that category of connection.<p>    \n'
                               '    --- If a list of allowed connection protocols is not specified for a '
                               'bus, then any category of connection can be bound\n'
                               '    --- to the bus.\n'
                               '    Allowed_Connection_Type: list of enumeration (Sampled_Data_Connection, '
                               'Immediate_Data_Connection,\n'
                               '        Delayed_Data_Connection, Port_Connection,\n'
                               '        Data_Access_Connection,\n'
                               '        Subprogram_Access_Connection) applies to (bus, device);\n'
                               '\n'
                               '    --- The <b>Allowed_Dispatch_Protocol</b> property specifies the thread '
                               'dispatch protocols are supported by a processor.\n'
                               '    --- That is, a thread may only be legally bound to the processor if the '
                               'specified thread dispatch protocol of the\n'
                               '    --- processor corresponds to the dispatch protocol required by the '
                               'thread.<p>\n'
                               '    --- If a list of allowed scheduling protocols is not specified for a '
                               'processor, then a thread with any dispatch\n'
                               '    --- protocol can be bound to and executed by the processor.\n'
                               '    Allowed_Dispatch_Protocol: list of Supported_Dispatch_Protocols applies '
                               'to (processor, virtual processor);\n'
                               '\n'
                               '    --- The <b>Allowed_Period</b> property specifies a set of allowed '
                               'periods for periodic tasks bound to a processor.<p> \n'
                               '    --- The period of every thread bound to the processor must fall within '
                               'one of the specified ranges.<p>\n'
                               '    --- If an allowed period is not specified for a processor, then there '
                               'are no restrictions on the periods of threads\n'
                               '    --- bound to that processor.\n'
                               '    Allowed_Period: list of Time_Range applies to (processor, system, '
                               'virtual processor);\n'
                               '\n'
                               '    --- The <b>Allowed_Physical_Access_Class</b> property specifies the '
                               'classifiers of processors, devices, memory, and\n'
                               '    --- buses that are allowed to be connected to the bus, i.e., whose '
                               'connection is supported by the bus.<p>\n'
                               '    --- If the property is not specified for a bus, then the bus may be used '
                               'to connect both devices and memory to the\n'
                               '    --- processor.\n'
                               '    Allowed_Physical_Access_Class: list of classifier (device, processor, '
                               'memory, bus) applies to (bus);\n'
                               '\n'
                               '    --- The <b>Allowed_Physical_Access</b> property specifies the '
                               'classifiers of processors, devices, memory, and buses\n'
                               '    --- that are allowed to be connected to the bus, i.e., whose connection '
                               'is supported by the bus.<p>\n'
                               '    --- If the property is not specified for a bus, then the bus may be used '
                               'to connect both devices and memory to the\n'
                               '    --- processor.\n'
                               '    Allowed_Physical_Access: list of reference (device, processor, memory, '
                               'bus) applies to (bus);\n'
                               '\n'
                               '    --- The <b>Memory_Protocol</b> property specifies memory access and '
                               'storage behaviors and restrictions.  Writeable data\n'
                               '    --- produced from software source text may only be bound to memory '
                               'components that have the <b>write_only</b> or\n'
                               '    --- <b>read_write</b> property value.       \n'
                               '    Memory_Protocol: enumeration (execute_only, read_only, write_only, '
                               'read_write) => read_write applies to (memory);\n'
                               '\n'
                               '    --- The <b>Runtime_Protection_Support</b> property specifies whether the '
                               'processor or virtual processor is able to\n'
                               '    --- support runtime enforcement of protected address spaces. Processes '
                               'and virtual processors bound to virtual\n'
                               '    --- processors and processors specify the demand for such runtime '
                               'enforcement.\n'
                               '    Runtime_Protection_Support: aadlboolean applies to (processor, virtual '
                               'processor);\n'
                               '\n'
                               '    --- The <b>Scheduling_Protocol</b> property specifies what scheduling '
                               'protocol the thread scheduler of the processor\n'
                               '    --- uses. The core standard does not prescribe a particular scheduling '
                               'protocol.<p>\n'
                               '    --- Scheduling protocols may result in schedulers that coordinate '
                               'scheduling of threads across multiple processors.\n'
                               '    Scheduling_Protocol: inherit list of Supported_Scheduling_Protocols '
                               'applies to (virtual processor, processor,\n'
                               '        system);\n'
                               '\n'
                               '    --- This property specifies if the processor can preempt a thread during '
                               'its execution. By default, if this property is\n'
                               '    --- not specified, the processor owns a preemptive scheduler.       \n'
                               '    Preemptive_Scheduler: aadlboolean applies to (processor);\n'
                               '\n'
                               '    --- The <b>Thread_Limit</b> property specifies the maximum number of '
                               'threads supported by the processor.\n'
                               '    Thread_Limit: aadlinteger 0 .. Max_Thread_Limit applies to (processor, '
                               'virtual processor);\n'
                               '\n'
                               '    --- The <b>Priority_Map</b> property specifies a mapping of AADL '
                               'priorities into priorities of the underlying real-time\n'
                               '    --- operating system. This map consists of a list of <b>aadlinteger</b> '
                               'pairs. \n'
                               '    Priority_Map: list of Priority_Mapping applies to (processor);\n'
                               '\n'
                               '    --- The <b>Priority_Mapping</b> type specifies a mapping of a single '
                               'AADL priority value into a single priority \n'
                               '    --- value of the underlying real-time operating system. This property is '
                               'used to define the elements of a  consists of\n'
                               '    --- a <b>Priority_Map</b>. \n'
                               '    Priority_Mapping: type record (\n'
                               '        Aadl_Priority: aadlinteger;\n'
                               '        RTOS_Priority: aadlinteger;);\n'
                               '\n'
                               '    --- The <b>Priority_Range</b> property specifies the range of thread '
                               'priority values that are acceptable to the\n'
                               '    --- processor.\n'
                               '    Priority_Range: range of aadlinteger applies to (processor, virtual '
                               'processor);\n'
                               '\n'
                               'end Deployment_Properties;',
 'Memory_Properties.aadl': '--AADLv2.3\n'
                           '--SAE Aerospace Standard AS5506D\n'
                           '--Appendix A: Predeclared Property Sets\n'
                           '-- @codegen-package org.osate.aadl2.contrib.memory\n'
                           'property set Memory_Properties is\n'
                           '\n'
                           '    --- The <b>Access_Right</b> property specifies the form of access that is '
                           'permitted for a component.  If associated with\n'
                           '    --- a requires access clause it specifies the intended access to the '
                           'component being accessed.  If associated with a\n'
                           '    --- provides access clause it specifies the type of access that is permitted '
                           'to the component for which access is\n'
                           '    --- provided.  This access may be direct through read and write access or '
                           'indirect through subprograms provided with\n'
                           '    --- the data type.  The provided access <b>Access_Right</b> must not exceed '
                           'the access right specified for the\n'
                           '    --- component itself.  The required access <b>Access_Right</b> must not '
                           'exceed the access right specified by the\n'
                           '    --- provides access or the component itself.\n'
                           '    Access_Right: Access_Rights => read_write applies to (data, bus, data '
                           'access, bus access);\n'
                           '\n'
                           '    --- The <b>Access_Rights</b> property type specifies the literals used to '
                           'indicate the form of access that is permitted\n'
                           '    --- for a component. \n'
                           '    Access_Rights: type enumeration (read_only, write_only, read_write, '
                           'by_method);\n'
                           '\n'
                           '    --- The <b>Access_Time</b> property specifies the range of execution time '
                           'during which the data component is being accessed. \n'
                           '    Access_Time: record (\n'
                           '        First: IO_Time_Spec;\n'
                           '        Last: IO_Time_Spec;) => [First => [Time => Start; Offset => 0ns .. '
                           '0ns;];\n'
                           '        Last => [Time => Completion; Offset => 0ns .. 0ns;];] applies to (data '
                           'access, data);\n'
                           '\n'
                           '    --- The <b>Allowed_Message_Size</b> property specifies the allowed range of '
                           'sizes for a block of data that can be\n'
                           '    --- transmitted by the bus hardware in a single transmission (in the absence '
                           'of packetization).<p>\n'
                           '    --- The expression defines the range of data message sizes, excluding any '
                           'header or packetization overheads added due\n'
                           '    --- to bus protocols, that can be sent in a single transmission over a bus.  '
                           'Messages whose sizes fall below this range\n'
                           '    --- will be padded. Messages whose sizes fall above this range must be '
                           'broken into two or more separately transmitted\n'
                           '    --- packets.\n'
                           '    Allowed_Message_Size: Size_Range applies to (bus);\n'
                           '\n'
                           '    --- The <b>Assign_Time</b> type specifies a time unit value used in a linear '
                           'estimation of the execution time\n'
                           '    --- required to move a block of bytes on a particular processor. The time '
                           'required is (<b>Number_of_Bytes * PerByte)\n'
                           '    --- + Fixed</b>\n'
                           '    Assign_Time: record (\n'
                           '        Fixed: Time_Range;\n'
                           '        PerByte: Time_Range;) applies to (processor);\n'
                           '\n'
                           '    --- The <b>Base_Address</b> property specifies the address of the first word '
                           'in the memory.  The addresses used to \n'
                           '    --- access successive words of memory are <b>Base_Address</b>, '
                           '<b>Base_Address + Word_Space</b>,\n'
                           '    --- <b>Base_Address + (Word_Count-1) * Word_Space</b>.<p>\n'
                           '    --- In the case of a memory component it indicates what its starting address '
                           'is.  In the case of a data component, data\n'
                           '    --- access, or port it indicates what its starting address is in the memory '
                           'it is bound to.  \n'
                           '    Base_Address: aadlinteger 0 .. Max_Base_Address applies to (memory, data, '
                           'data access, port);\n'
                           '\n'
                           '    --- The <b>Device_Register_Address</b> property specifies the address of a '
                           'device register that is represented by a port\n'
                           '    --- associated with a device.  This property is optional.  Device ports may '
                           'be represented by a source text variable as\n'
                           '    --- part of the device driver software.\n'
                           '    Device_Register_Address: aadlinteger applies to (port, feature group);\n'
                           '\n'
                           '    --- The <b>Read_Time</b> property specifies a time unit value used in a '
                           'linear estimation of the execution time\n'
                           '    --- required to read a block of bytes from memory. The time required is '
                           '(<b>Number_of_Bytes * PerByte) + Fixed</b>\n'
                           '    Read_Time: record (\n'
                           '        Fixed: Time_Range;\n'
                           '        PerByte: Time_Range;) applies to (memory);\n'
                           '\n'
                           '    --- The <b>Code_Size</b> property specifies the size of the static code and '
                           'read-only data that results when the\n'
                           '    --- associated source text is compiled, linked, bound and loaded in the '
                           'final system.<p>\n'
                           '    Code_Size: Size applies to (data, thread, thread group, process, system, '
                           'subprogram, processor, virtual processor,\n'
                           '        virtual bus, device);\n'
                           '\n'
                           '    --- The <b>Data_Size</b> property specifies the size of the readable and '
                           'writeable data that results when the\n'
                           '    --- associated source text is compiled, linked, bound and loaded in the '
                           'final system.  In the case of data types, it\n'
                           '    --- specifies the maximum size required to hold a value of an instance of '
                           'the data type.<p> \n'
                           '    Data_Size: Size applies to (data, feature, subprogram, thread, thread group, '
                           'process, system, processor,\n'
                           '        virtual processor, virtual bus, bus, device);\n'
                           '\n'
                           '    --- The <b>Heap_Size</b> property specifies the maximum heap size '
                           'requirements of a thread or subprogram.<p>\n'
                           '    Heap_Size: Size applies to (thread, subprogram, thread group, process, '
                           'system, processor, virtual processor,\n'
                           '        device);\n'
                           '\n'
                           '    --- The <b>Stack_Size</b> property specifies the maximum size of the stack '
                           'used by a processor executive, a device\n'
                           '    ---  driver, a thread or a subprogram during execution.<p>\n'
                           '    Stack_Size: Size applies to (thread, subprogram, processor, virtual '
                           'processor, device);\n'
                           '\n'
                           '\t--- The <b>Memory_Size</b> property specifies the size of the memory.\n'
                           '\tMemory_Size: Size\n'
                           '\t\tapplies to (memory, system, processor, virtual processor);\n'
                           '\n'
                           '    --- The <b>Word_Size</b> property specifies the size of the smallest '
                           'independently readable and writeable unit of\n'
                           '    --- storage in the memory.<p> \n'
                           '    Word_Size: Size => 8 bits applies to (memory);\n'
                           '\n'
                           '    --- The <b>Word_Space</b> specifies the interval between successive '
                           'addresses used for successive words of memory.  A\n'
                           '    --- value greater than 1 means the addresses used to access words are not '
                           'contiguous integers.<p>  \n'
                           '    --- The default value is 1.\n'
                           '    Word_Space: aadlinteger 1 .. Max_Word_Space => 1 applies to (memory);\n'
                           '\n'
                           '    --- The <b>Write_Time</b> property specifies a time unit value used in a '
                           'linear estimation of the execution time\n'
                           '    --- required to write a block of bytes to memory. The time required is '
                           '(<b>Number_of_Bytes * PerByte) + Fixed</b>\n'
                           '    Write_Time: record (\n'
                           '        Fixed: Time_Range;\n'
                           '        PerByte: Time_Range;) applies to (memory);\n'
                           '\n'
                           '\t--- <b>DEPRECATED</b><p>\n'
                           '\t--- Replaced by Code_Size\n'
                           '\tSource_Code_Size: Size\n'
                           '\t\tapplies to (data, thread, thread group, process, system, subprogram, '
                           'processor, virtual processor, device);\n'
                           '\t\t\n'
                           '\t--- <b>DEPRECATED</b><p>\n'
                           '\t--- Replaced by Data_Size\n'
                           '\tSource_Data_Size: Size\n'
                           '\t\tapplies to (data, feature, subprogram, thread, thread group, process, '
                           'system, processor, virtual processor, device);\n'
                           '\t\t\n'
                           '\t--- <b>DEPRECATED</b><p>\n'
                           '\t--- Replaced by Heap_Size\n'
                           '\tSource_Heap_Size: Size\n'
                           '\t\tapplies to (thread, subprogram);\n'
                           '\t\n'
                           '\t--- <b>DEPRECATED</b><p>\n'
                           '\t--- Replaced by Stack_Size\n'
                           '\tSource_Stack_Size: Size\n'
                           '\t\tapplies to (thread, subprogram, processor, virtual processor, device);\n'
                           '\t\n'
                           '\t--- <b>DEPRECATED</b><p>\n'
                           '\t--- Replaced by Memory_Size\n'
                           '\tByte_Count: aadlinteger 0 .. Max_Byte_Count\n'
                           '\t\tapplies to (memory); -- Deprecated. use memory_size\n'
                           '\t\n'
                           '\n'
                           'end Memory_Properties;\n',
 'Modeling_Properties.aadl': '--AADLv2.3\n'
                             '--SAE Aerospace Standard AS5506D\n'
                             '--Appendix A: Predeclared Property Sets\n'
                             '-- @codegen-package org.osate.aadl2.contrib.modeling\n'
                             'property set Modeling_Properties is\n'
                             '\n'
                             '    --- The <b>Acceptable_Array_Size</b> property specifies acceptable values '
                             'for the array size in each dimension.  This\n'
                             '    --- property can be used to impose a constraint on the array dimension '
                             'size if it has not been specified to be refined separately.\n'
                             '    Acceptable_Array_Size: list of Array_Size_Range applies to (subcomponent, '
                             'feature);\n'
                             '    Array_Size_Range: type range of aadlinteger;\n'
                             '\n'
                             '    --- The <b>Classifier_Matching_Rule</b> property specifies acceptable '
                             'matches of classifiers between the source and\n'
                             '    --- the destination of a connection. The semantics of each of the rules '
                             'are defined in sections 9.1 and 9.5\n'
                             '    Classifier_Matching_Rule: inherit enumeration (Classifier_Match, '
                             'Equivalence, Subset,\n'
                             '        Conversion) => Classifier_Match applies to (connection, component '
                             'implementation);\n'
                             '\n'
                             '    --- The <b>Classifier_Substitution_Rule</b> property specifies acceptable '
                             'substitutions of classifiers in a refined to\n'
                             '    --- declaration.\n'
                             '    Classifier_Substitution_Rule: inherit enumeration (Classifier_Match, '
                             'Type_Extension,\n'
                             '        Signature_Match) => Type_Extension applies to (classifier, '
                             'subcomponent, feature);\n'
                             '\n'
                             '    --- The <b>Implemented_As</b> property specifies a system implementation '
                             'that describes how the internals of a\n'
                             '    --- execution platform component are realized.  This allows systems to be '
                             'modeled as a layered architecture using the\n'
                             '    --- execution platform as a layering abstraction (see Section 14).\n'
                             '    Implemented_As: classifier (system implementation, abstract '
                             'implementation) applies to (memory, bus, virtual bus,\n'
                             '        device, virtual processor, processor, system);\n'
                             '\n'
                             '    --- The <b>Prototype_Substitution_Rule</b> property specifies acceptable '
                             'classifiers supplied as actual in a prototype\n'
                             '    --- binding.    \n'
                             '    Prototype_Substitution_Rule: inherit enumeration (Classifier_Match, '
                             'Type_Extension,\n'
                             '        Signature_Match) => Type_Extension applies to (prototype, '
                             'classifier);\n'
                             '\n'
                             'end Modeling_Properties;',
 'Programming_Properties.aadl': '--AADLv2.3\n'
                                '--SAE Aerospace Standard AS5506D\n'
                                '--Appendix A: Predeclared Property Sets\n'
                                '-- @codegen-package org.osate.aadl2.contrib.programming\n'
                                'property set Programming_Properties is\n'
                                '\n'
                                '    --- The <b>Activate_Entrypoint</b> property specifies the name of a '
                                'subprogram classifier.  This classifier represents\n'
                                '    --- a subprogram in the source text that will execute when a thread or '
                                'device are activated as part of a mode switch.<p>  \n'
                                '    --- The subprogram in the source text must be visible in and callable '
                                'from the outermost program scope, as defined by\n'
                                '    --- the scope and visibility rules of the applicable implementation '
                                'language.  The source language annex of this\n'
                                '    --- standard defines acceptable parameter and result signatures for the '
                                'entry point subprogram.\n'
                                '    Activate_Entrypoint: classifier (subprogram classifier) applies to '
                                '(thread, device, processor, virtual processor);\n'
                                '\n'
                                '    --- The <b>Activate_Entrypoint_Call</b>_Sequence property specifies the '
                                'name of a subprogram call sequence.  This call\n'
                                '    --- sequence will execute after a thread has been dispatched.  If the '
                                'property is specified for a provided subprogram,\n'
                                '    --- event port, or event data port feature, then this entrypoint is '
                                'chosen when the corresponding call, event, or event\n'
                                '    --- data arrives instead of the compute entrypoint specified for the '
                                'containing thread.<p>\n'
                                '    --- The named call sequence must exist in the source text as '
                                'parameterless function, which performs the calls and \n'
                                '    --- passes the appropriate port and parameter values as actual '
                                'parameters.  This function may be generated from AADL\n'
                                '    --- This function must be visible in and callable from the outermost '
                                'program scope, as defined by the scope and\n'
                                '    --- visibility rules of the applicable implementation language.  The '
                                'source language annex of this standard defines\n'
                                '    --- acceptable parameter and result signatures for the entry point '
                                'subprogram.\n'
                                '    Activate_Entrypoint_Call_Sequence: reference (subprogram call sequence) '
                                'applies to (thread, device);\n'
                                '\n'
                                '    --- The <b>Activate_Entrypoint_Source_Text</b> property specifies the '
                                'name of a source text code sequence that will\n'
                                '    --- execute when a thread is activated.<p>  \n'
                                '    --- The named code sequence in the source text must be visible in and '
                                'callable from the outermost program scope, as\n'
                                '    --- defined by the scope and visibility rules of the applicable source '
                                'language.  The source language annex of this\n'
                                '    --- standard defines acceptable parameter and result signatures for the '
                                'entrypoint subprogram.\n'
                                '    Activate_Entrypoint_Source_Text: aadlstring applies to (thread, device, '
                                'virtual processor, processor);\n'
                                '\n'
                                '    --- The <b>Compute_Entrypoint</b> property specifies the name of a '
                                'subprogram classifier.  This classifier represents\n'
                                '    --- a subprogram in the source text that will execute after a thread '
                                'has been dispatched.  If the property is\n'
                                '    --- specified for a provided subprogram, event port, or event data port '
                                'feature, then this entrypoint is chosen when\n'
                                '    --- the corresponding call, event, or event data arrives instead of the '
                                'compute entrypoint specified for the containing\n'
                                '    --- thread.<p>\n'
                                '    --- The subprogram in the source text must be visible in and callable '
                                'from the outermost program scope, as defined by\n'
                                '    -- the scope and visibility rules of the applicable implementation '
                                'language.  The source language annex of this\n'
                                '    --- standard defines acceptable parameter and result signatures for the '
                                'entry point subprogram.\n'
                                '    Compute_Entrypoint: classifier (subprogram classifier) applies to '
                                '(thread, device, subprogram access, event port,\n'
                                '        event data port);\n'
                                '\n'
                                '    --- The <b>Compute_Entrypoint_Call_Sequence</b> property specifies the '
                                'name of a subprogram call sequence.  This call\n'
                                '    --- sequence will execute after a thread has been dispatched.  If the '
                                'property is specified for a provided subprogram,\n'
                                '    --- event port, or event data port feature, then this entrypoint is '
                                'chosen when the corresponding call, event, or event\n'
                                '    --- data arrives instead of the compute entrypoint specified for the '
                                'containing thread.<p>\n'
                                '    --- The named call sequence must exist in the source text as '
                                'parameterless function, which performs the calls and\n'
                                '    --- passes the appropriate port and parameter values as actual '
                                'parameters.  This function may be generated from\n'
                                '    --- AADL. This function must be visible in and callable from the '
                                'outermost program scope, as defined by the scope\n'
                                '    --- and visibility rules of the applicable implementation language.  '
                                'The source language annex of this standard defines\n'
                                '    --- acceptable parameter and result signatures for the entry point '
                                'subprogram.\n'
                                '    Compute_Entrypoint_Call_Sequence: reference (subprogram call sequence) '
                                'applies to (thread, device,\n'
                                '        subprogram access, event port, event data port);\n'
                                '\n'
                                '    --- The <b>Compute_Entrypoint_Source_Text</b> property specifies the '
                                'name of a source text code sequence that will\n'
                                '    --- execute after a thread has been dispatched.  If the property is '
                                'specified for a provided subprogram, event port, or\n'
                                '    --- event data port feature, then this entrypoint is chosen when the '
                                'corresponding call, event, or event data arrives\n'
                                '    --- instead of the compute entrypoint specified for the containing '
                                'thread.<p>\n'
                                '    --- The named code sequence in the source text must be visible in and '
                                'callable from the outermost program scope, as\n'
                                '    --- defined by the scope and visibility rules of the applicable '
                                'implementation language.  The source language annex of\n'
                                '    --- this standard defines acceptable parameter and result signatures '
                                'for the entry point subprogram.\n'
                                '    Compute_Entrypoint_Source_Text: aadlstring applies to (thread, device, '
                                'subprogram access, event port,\n'
                                '        event data port);\n'
                                '\n'
                                '    --- The <b>Deactivate_Entrypoint</b> property specifies the name of a '
                                'subprogram classifier.  This classifier\n'
                                '    --- represents a subprogram in the source text that will execute when a '
                                'thread is deactivated as part of a mode\n'
                                '    --- switch.<p>  \n'
                                '    --- The subprogram in the source text must be visible in and callable '
                                'from the outermost program scope, as defined\n'
                                '    --- by the scope and visibility rules of the applicable implementation '
                                'language.  The source language annex of this \n'
                                '    --- standard defines acceptable parameter and result signatures for the '
                                'entry point subprogram.\n'
                                '    Deactivate_Entrypoint: classifier (subprogram classifier) applies to '
                                '(thread, device);\n'
                                '\n'
                                '    --- The <b>Deactivate_Entrypoint_Call_Sequence</b> property specifies '
                                'the name of a subprogram call sequence.  This\n'
                                '    --- call sequence will execute when a thread is deactivated as part of '
                                'a mode switch.<p>  \n'
                                '    --- The named call sequence must exist in the source text as '
                                'parameterless function, which performs the calls and\n'
                                '    --- passes the appropriate port and parameter values as actual '
                                'parameters.  This function may be generated from AADL.\n'
                                '    --- This function must be visible in and callable from the outermost '
                                'program scope, as defined by the scope and\n'
                                '    --- visibility rules of the applicable implementation language.  The '
                                'source language annex of this standard defines\n'
                                '    --- acceptable parameter and result signatures for the entry point '
                                'subprogram.\n'
                                '    Deactivate_Entrypoint_Call_Sequence: reference (subprogram call '
                                'sequence) applies to (thread, device);\n'
                                '\n'
                                '    --- The <b>Deactivate_Entrypoint_Source_Text</b> property specifies the '
                                'name of a source text code sequence that will\n'
                                '    --- execute when a thread is deactivated.<p>  \n'
                                '    --- The named code sequence in the source text must be visible in and '
                                'callable from the outermost program scope, as\n'
                                '    --- defined by the scope and visibility rules of the applicable '
                                'implementation language.  The source language annex of\n'
                                '    --- this standard defines acceptable parameter and result signatures '
                                'for the entry point subprogram\n'
                                '    Deactivate_Entrypoint_Source_Text: aadlstring applies to (thread);\n'
                                '\n'
                                '    --- The <b>Finalize_Entrypoint</b> property specifies the name of a '
                                'subprogram classifier.  This classifier represents\n'
                                '    --- a subprogram in the source text that will execute when a thread is '
                                'finalized.<p>  \n'
                                '    --- The subprogram in the source text must be visible in and callable '
                                'from the outermost program scope, as defined by\n'
                                '    --- the scope and visibility rules of the applicable implementation '
                                'language.  The source language annex of this\n'
                                '    ---  standard defines acceptable parameter and result signatures for '
                                'the entry point subprogram.\n'
                                '    Finalize_Entrypoint: classifier (subprogram classifier) applies to '
                                '(thread, device);\n'
                                '\n'
                                '    --- The <b>Finalize_Entrypoint_Call_Sequence</b> property specifies the '
                                'name of a subprogram call sequence.  This call\n'
                                '    --- sequence will execute when a thread is finalized.<p>  \n'
                                '    --- The named call sequence must exist in the source text as '
                                'parameterless function, which performs the calls and\n'
                                '    --- passes the appropriate port and parameter values as actual '
                                'parameters.  This function may be generated from AADL.\n'
                                '    --- This function must be visible in and callable from the outermost '
                                'program scope, as defined by the scope and\n'
                                '    --- visibility rules of the applicable implementation language.  The '
                                'source language annex of this standard defines\n'
                                '    --- acceptable parameter and result signatures for the entry point '
                                'subprogram\n'
                                '    Finalize_Entrypoint_Call_Sequence: reference (subprogram call sequence) '
                                'applies to (thread, device);\n'
                                '\n'
                                '    --- The <b>Finalize_Entrypoint_Source_Text</b> property specifies the '
                                'name of a source text code sequence that will\n'
                                '    --- execute when a thread is finalized.<p>  \n'
                                '    --- The named code sequence in the source text must be visible in and '
                                'callable from the outermost program scope, as\n'
                                '    --- defined by the scope and visibility rules of the applicable '
                                'implementation language.  The source language annex of\n'
                                '    --- this standard defines acceptable parameter and result signatures '
                                'for the entrypoint subprogram.\n'
                                '    Finalize_Entrypoint_Source_Text: aadlstring applies to (thread, '
                                'device);\n'
                                '\n'
                                '    --- The <b>Initialize_Entrypoint</b> property specifies the name of a '
                                'subprogram classifier.  This classifier \n'
                                '    --- represents a subprogram in the source text that will execute when a '
                                'thread is initialized.<p>  \n'
                                '    --- The subprogram in the source text must be visible in and callable '
                                'from the outermost program scope, as defined by\n'
                                '    --- the scope and visibility rules of the applicable implementation '
                                'language.  The source language annex of this\n'
                                '    --- standard defines acceptable parameter and result signatures for the '
                                'entry point subprogram.\n'
                                '    Initialize_Entrypoint: classifier (subprogram classifier) applies to '
                                '(thread, device);\n'
                                '\n'
                                '    --- The <b>Initialize_Entrypoint_Call_Sequence</b> property specifies '
                                'the name of a subprogram call sequence.  This\n'
                                '    --- call sequence will execute when a thread is initialized.<p>  \n'
                                '    --- The named call sequence must exist in the source text as '
                                'parameterless function, which performs the calls and\n'
                                '    --- passes the appropriate port and parameter values as actual '
                                'parameters.  This function may be generated from AADL.\n'
                                '    --- This function must be visible in and callable from the outermost '
                                'program scope, as defined by the scope and \n'
                                '    --- visibility rules of the applicable implementation language.  The '
                                'source language annex of this standard defines\n'
                                '    --- acceptable parameter and result signatures for the entry point '
                                'subprogram.\n'
                                '    Initialize_Entrypoint_Call_Sequence: reference (subprogram call '
                                'sequence) applies to (thread, device);\n'
                                '\n'
                                '    --- The <b>Initialize_Entrypoint_Source_Text</b> property specifies the '
                                'name of a source text code sequence that will\n'
                                '    --- execute when a thread is to be initialized.<p>  \n'
                                '    --- The named code sequence in the source text must be visible in and '
                                'callable from the outermost program scope, as\n'
                                '    --- defined by the scope and visibility rules of the applicable '
                                'implementation language.  The source language annex\n'
                                '    --- of this standard defines acceptable parameter and result signatures '
                                'for the entrypoint subprogram.\n'
                                '    Initialize_Entrypoint_Source_Text: aadlstring applies to (thread, '
                                'device);\n'
                                '\n'
                                '    --- The <b>Recover_Entrypoint</b> property specifies the name of a '
                                'subprogram classifier.  This classifier represents\n'
                                '    --- a subprogram in the source text that will execute when a thread is '
                                'in recovery.<p>  \n'
                                '    --- The subprogram in the source text must be visible in and callable '
                                'from the outermost program scope, as defined by\n'
                                '    --- the scope and visibility rules of the applicable implementation '
                                'language.  The source language annex of this\n'
                                '    --- standard defines acceptable parameter and result signatures for the '
                                'entry point subprogram.\n'
                                '    Recover_Entrypoint: classifier (subprogram classifier) applies to '
                                '(thread, device);\n'
                                '\n'
                                '    --- The <b>Recover_Entrypoint_Call_Sequence</b> property specifies the '
                                'name of a subprogram call sequence.  This call\n'
                                '    --- sequence will execute when a thread is in recovery.<p>  \n'
                                '    --- The named call sequence must exist in the source text as '
                                'parameterless function, which performs the calls and\n'
                                '    --- passes the appropriate port and parameter values as actual '
                                'parameters.  This function may be generated from AADL.\n'
                                '    --- This function must be visible in and callable from the outermost '
                                'program scope, as defined by the scope and\n'
                                '    --- visibility rules of the applicable implementation language.  The '
                                'source language annex of this standard defines\n'
                                '    --- acceptable parameter and result signatures for the entry point '
                                'subprogram.\n'
                                '    Recover_Entrypoint_Call_Sequence: reference (subprogram call sequence) '
                                'applies to (thread, device);\n'
                                '\n'
                                '    --- The <b>Recovery_Entrypoint_Source_Text</b> property specifies the '
                                'name of a source text code sequence that will execute when\n'
                                '    --- a thread is recovering from a fault.<p>  \n'
                                '    --- The named code sequence in the source text must be visible in and '
                                'callable from the outermost program scope, as defined by the\n'
                                '    --- scope and visibility rules of the applicable implementation '
                                'language.  The source language annex of this standard defines acceptable\n'
                                '    --- parameter and result signatures for the entrypoint subprogram.\n'
                                '    Recover_Entrypoint_Source_Text: aadlstring applies to (thread, '
                                'device);\n'
                                '\n'
                                '    --- The <b>Source_Language</b> property specifies an applicable '
                                'programming language.<p> \n'
                                '    --- The property type is enumeration.  The standard enumeration '
                                'literals are Ada, C, and Simulink� for software\n'
                                '    --- categories.  Other enumeration literals are permitted.<p>  \n'
                                '    --- There is no default value for the source language property.<p>  \n'
                                '    --- Where a source language is specified for a component, the source '
                                'text associated with that component must comply\n'
                                '    --- with the applicable programming language standard.  Where a source '
                                'language is not specified, a method of\n'
                                '    --- processing may infer a source language from the syntax of the '
                                'source text pathname.  A method of processing may \n'
                                '    --- establish a default value for the source language property.<p>\n'
                                '    --- Where a source language property is specified for a component, any '
                                'specified source language and any specified \n'
                                '    --- source text for the complete set of software subcomponents of that '
                                'component must comply with the applicable\n'
                                '    --- language standard.<p>\n'
                                '    --- Multiple source languages, and source text written in those '
                                'languages, are compliant with a specified source\n'
                                '    --- language if the applicable language standard permits mixing source '
                                'text units of those languages within the same\n'
                                '    --- program.<p>\n'
                                '    --- A method of processing may accept data produced by processing a '
                                'source text file, for example an object file\n'
                                '    --- produced by compiling source text may be considered compliant with '
                                'the applicable language standard.  A method of\n'
                                '    --- processing may accept non-standard source languages.  A method of '
                                'processing may restrict itself to specific source\n'
                                '    --- languages, either standard or non-standard.<p>\n'
                                '    --- The source language associated with processor or virtual processor '
                                'indicates the source language of any software\n'
                                '    --- that implements virtual processor or processor functionality.\n'
                                '    Source_Language: inherit list of Supported_Source_Languages applies to '
                                '(subprogram, data, thread, thread group,\n'
                                '        process, system, bus, device,\n'
                                '        processor, virtual processor, virtual bus);\n'
                                '\n'
                                '    --- The <b>Source_Name</b> property specifies a source declaration or '
                                'source name within the associated source text\n'
                                '    --- that corresponds to a feature defining identifier.<p>  \n'
                                '    --- The default value is defined in the source language annex of this '
                                'standard\n'
                                '    Source_Name: aadlstring applies to (data, port, subprogram, parameter, '
                                'virtual bus, virtual processor);\n'
                                '\n'
                                '    --- Each string is interpreted as a POSIX pathname and must satisfy the '
                                'syntax and semantics for path names as defined\n'
                                '    --- in the POSIX standard.  Extensions to the standard POSIX pathname '
                                'syntax and semantics are permitted.  For example,\n'
                                '    --- environment variables and regular expressions are permitted.  '
                                'Special characters may be used to assist in\n'
                                '    --- configuration management and version control.<p>\n'
                                '    --- If the first character of a pathname is . (dot) then the path is '
                                'relative to the directory in which the file\n'
                                '    --- containing the AADL specification text is stored.  Otherwise, the '
                                'method of processing may define the default\n'
                                '    --- directory used to locate the file designated by a pathname.<p>\n'
                                '    --- There is no standard default value for the source text '
                                'property.<p>\n'
                                '    --- The combined source text contained in all files named in the '
                                'associated expression must form one or more separately\n'
                                '    --- compilable units as defined in the applicable source language '
                                'standard.\n'
                                '    Source_Text: inherit list of aadlstring applies to (data, port, '
                                'subprogram, thread, thread group, process, system,\n'
                                '        virtual bus, virtual processor,\n'
                                '        memory, bus, device, processor, parameter, feature group, aadl '
                                'package);\n'
                                '\n'
                                '    --- The <b>Supported_Source_Language</b> property specifies the source '
                                'language(s) supported by a processor.<p>  \n'
                                '    --- The source language of every software component that may be '
                                'accessed by any thread bound to a processor must\n'
                                '    --- appear in the list of allowed source languages for that '
                                'processor.<p>\n'
                                '    --- If an allowed source language list is not specified, then there are '
                                'no restrictions on the source. \n'
                                '    Supported_Source_Language: list of Supported_Source_Languages applies '
                                'to (processor, virtual processor, system);\n'
                                '\n'
                                '    --- The <b>Type_Source_Name</b> property specifies a source declaration '
                                'or source name within the associated source\n'
                                '    --- text that corresponds to a feature defining identifier.<p>  \n'
                                '    --- The default value is defined in the source language annex of this '
                                'standard.\n'
                                '    Type_Source_Name: aadlstring applies to (data, port, subprogram);\n'
                                '\n'
                                '    --- The <b>Hardware_Description_Source_Text</b> property specifies a '
                                'list of files that contain source text of a\n'
                                '    --- hardware description in a hardware description language.<p>  \n'
                                '    --- Each string is interpreted as a POSIX pathname and must satisfy the '
                                'syntax and semantics for path names as defined\n'
                                '    --- in the POSIX standard. Extensions to the standard POSIX pathname '
                                'syntax and semantics are permitted.  For example,\n'
                                '    --- environment variables and regular expressions are permitted.  '
                                'Special characters may be used to assist in\n'
                                '    --- configuration management and version control.<p>\n'
                                '    --- If the first character of a pathname is . (dot) then the path is '
                                'relative to the directory in which the file\n'
                                '    --- containing the AADL specification text is stored.  Otherwise, the '
                                'method of processing may define the default\n'
                                '    --- directory used to locate the file designated by a pathname.\n'
                                '    Hardware_Description_Source_Text: inherit list of aadlstring applies to '
                                '(memory, bus, device, processor, system);\n'
                                '\n'
                                '    --- The <b>Hardware_Source_Language</b> property specifies an '
                                'applicable hardware description language.<p> \n'
                                '    --- The hardware description source text associated with a hardware '
                                'component is written in a hardware description\n'
                                '    --- language.  \n'
                                '    Hardware_Source_Language: Supported_Hardware_Source_Languages applies '
                                'to (memory, bus, device, processor, system);\n'
                                '\n'
                                '    --- The <b>Device_Driver</b> property specifies a reference to an '
                                'abstract component implementation that contains an\n'
                                '    --- instance of a driver subprogram or an instance of an (ISR) '
                                'thread. \n'
                                '    Device_Driver: classifier (abstract implementation) applies to '
                                '(device);\n'
                                '\n'
                                'end Programming_Properties;\n',
 'Thread_Properties.aadl': '--AADLv2.3\n'
                           '--SAE Aerospace Standard AS5506D\n'
                           '--Appendix A: Predeclared Property Sets\n'
                           '-- @codegen-package org.osate.aadl2.contrib.thread\n'
                           'property set Thread_Properties is\n'
                           '\n'
                           '    --- The <b>Dispatch_Protocol</b> property specifies the dispatch behavior '
                           'for a thread.<p>\n'
                           '    --- A method used to construct a actual system from a specification is '
                           'permitted to support only a subset of the\n'
                           '    --- standard scheduling protocols.  A method used to construct a actual '
                           'system is permitted to support additional\n'
                           '    --- non-standard scheduling protocols.\n'
                           '    Dispatch_Protocol: Supported_Dispatch_Protocols applies to (thread, device, '
                           'virtual processor);\n'
                           '\n'
                           '    --- The <b>Dispatch_Trigger</b> property specifies the list of ports that '
                           'can trigger the dispatch of a thread or\n'
                           '    --- device. \n'
                           '    Dispatch_Trigger: list of reference (port) applies to (device, thread);\n'
                           '\n'
                           '    --- The <b>Dispatch_Able</b> property specifies whether a thread should be '
                           'dispatched. Threads can be activated for\n'
                           '    --- dispatch in given modes, which is specified as part of the subcomponent '
                           'declaration of the component using the\n'
                           '    --- thread. In some cases the thread itself may have modes and that mode '
                           'determines whether the thread is active or\n'
                           '    --- idle. For example, various combinations of low level control threads may '
                           'be active or idle at various points in\n'
                           '    --- time.<p>\n'
                           '    --- Expressing this through modes in the enclosing component would lead to '
                           'possibly having to model many mode\n'
                           '    --- combinations of subcomponents. Specification of zero '
                           '<b>compute_execution_time</b> for a thread indicates that\n'
                           '    --- thread is dispatched and its application code decides there is nothing '
                           'to do.\n'
                           '    Dispatch_Able: aadlboolean applies to (thread);\n'
                           '\n'
                           '    --- The <b>POSIX_Scheduling_Policy</b> property is used for the modeling of '
                           'the scheduling protocols defined by \n'
                           '    --- POSIX 1003.1b. Such a property specifies the policy assign to a given '
                           'thread. The policy may be either\n'
                           '    --- <b>SCHED_FIFO</b>, <b>SCHED_RR</b> or <b>SCHED_OTHER</b>. In a POSIX '
                           '1003.1b architecture, the policy allows the\n'
                           '    --- scheduler to choose the thread to run when several threads have the same '
                           'fixed priority. If a thread does not \n'
                           '    --- define the <b>POSIX_Scheduling_Policy</B> property, it has by default '
                           'the <b>SCHED_FIFO</b> policy. The policy\n'
                           '    --- semantics are:<p>\n'
                           '    --- <ul><li><b>SCHED_FIFO</b> : this policy implements a FIFO scheduling '
                           'protocol on the set of equal fixed priority :\n'
                           '    --- a thread stays on the processor until it has terminated or until a '
                           'highest priority thread is released.</li><p> \n'
                           '    --- <li><b>SCHED_RR</b> : this policy is similar to <b>SCHED_FIFO</b> except '
                           'that the quantum is used. At the end of the\n'
                           '    --- quantum, the running thread is pre-empted from the processor and a equal '
                           'priority thread has to be released.</li><p> \n'
                           '    --- <li><b>SCHED_OTHER</b> : its semantic is defined by POSIX policy '
                           'implementers. This policy usually implements a\n'
                           '    --- timing sharing scheduling protocol.</ul> \n'
                           '    POSIX_Scheduling_Policy: enumeration (SCHED_FIFO, SCHED_RR, SCHED_OTHERS) '
                           'applies to (thread, thread group);\n'
                           '\n'
                           '    --- The <b>Priority</b> property specifies the priority of the thread that '
                           'is taken into consideration by some\n'
                           '    --- scheduling protocols in scheduling the execution order of threads.  When '
                           'applied to data or data access, it\n'
                           '    --- specifies priority for concurrency control protocols that use priority '
                           'based conflict resolution. A larger value\n'
                           '    --- represents a higher priority.<p>\n'
                           '    --- The property type is <b>aadlinteger</b>.  Its value is expected to be '
                           'within the range of priority values supported\n'
                           '    --- by a given processor. \n'
                           '    Priority: inherit aadlinteger applies to (thread, thread group, process, '
                           'system, device, data, data access);\n'
                           '\n'
                           '    --- This property specifies the criticality level of a thread. This property '
                           'is used by maximum urgency first\n'
                           '    --- scheduling protocols. Such a property can also be used by any project '
                           'specific  scheduling protocols. \n'
                           '    Criticality: aadlinteger applies to (thread, thread group);\n'
                           '\n'
                           '    --- The <b>Time_Slot</b> property specifies statically allocated slots on a '
                           'timeline.  This property is used by\n'
                           '    --- scheduling protocols with a time slot allocation approach, such as the '
                           'protocol for scheduling partitions on a\n'
                           '    --- static timeline \n'
                           '    Time_Slot: list of aadlinteger applies to (thread, thread group, process, '
                           'virtual processor, system);\n'
                           '\n'
                           '    --- The <b>Concurrency_Control_Protocol</b> property specifies the '
                           'concurrency control protocol used to ensure mutually\n'
                           '    --- exclusive access, i.e., a critical region, to a shared data component.  '
                           'If no value is specified the default value \n'
                           '    --- is <b>None_Specified</b>, i.e., no concurrency control protocol.\n'
                           '    Concurrency_Control_Protocol: Supported_Concurrency_Control_Protocols '
                           'applies to (data, data access);\n'
                           '\n'
                           '    --- The <b>Urgency</b> property specifies the urgency with which an event at '
                           'an in port is to be serviced relative to\n'
                           '    --- other events arriving at or queued at other in ports of the same '
                           'thread.  A numerically larger number represents\n'
                           '    --- higher urgency\n'
                           '    Urgency: aadlinteger 0 .. Max_Urgency applies to (port, subprogram);\n'
                           '\n'
                           '    --- The <b>Dequeue_Protocol</b> property specifies different dequeuing '
                           'options.<p>\n'
                           '    --- <ul><li><b>OneItem</b>: (default) a single frozen item is dequeued at '
                           'input time and made available to the source\n'
                           '    ---  text unless the queue is empty. The <b>Next_Value</b> service call has '
                           'no effect.</li><p>\n'
                           '    --- <li><b>AllItems</b>: all items that are frozen at input time are '
                           'dequeued and made available to the source text via\n'
                           '    --- the port variable, unless the queue is empty.  Individual items become '
                           'accessible as port variable value through\n'
                           '    --- the <b>Next_Value</b> service call. Any element in the frozen queue that '
                           'are not retrieved through the\n'
                           '    --- <b>Next_Value</b> service call are discarded, i.e., are removed from the '
                           'queue and are not available at the next\n'
                           '    --- input time.</li><p> \n'
                           '    --- <li><b>MultipleItems</b>: multiple items can be dequeued one at a time '
                           'from the frozen queue and made available to\n'
                           '    --- the source text via the port variable.  One item is dequeued and its '
                           'value made available via the port variable with\n'
                           '    --- each <b>Next_Value</b> service call.  Any items not dequeued remain in '
                           'the queue and are available at the next\n'
                           '    --- input time.</li><p>\n'
                           '    --- If the <b>Dequeued_Items</b> property is set, then it imposes a maximum '
                           'on the number of elements that are made\n'
                           '    --- accessible to a thread at input time when the <b>Dequeue_Protocol</b> '
                           'property is set to <b>AllItems</b> or\n'
                           '    --- <b>MultipleItems</b>.</li><p>\n'
                           '    --- The default property value is <b>OneItem</b>.\n'
                           '    Dequeue_Protocol: enumeration (OneItem, MultipleItems, AllItems) => OneItem '
                           'applies to (event port,\n'
                           '        event data port);\n'
                           '\n'
                           '    --- The <b>Dequeued_Items<b> property specifies the maximum number of items '
                           'that are made available to the application\n'
                           '    --- via a port variable for event or event data ports when the input is '
                           'frozen at input time.  Its value cannot exceed\n'
                           '    --- that of the <b>Queue_Size</b> property for the same port.  See also '
                           '<b>Dequeue_Protocol</b> property.\n'
                           '    Dequeued_Items: aadlinteger applies to (event port, event data port);\n'
                           '\n'
                           '    --- The <b>Mode_Transition_Response</b> property specifies whether the mode '
                           'transition occurs immediately due to an\n'
                           '    --- emergency, or whether it is planned in that the completion of thread '
                           'execution can be coordinated before performing\n'
                           '    --- the mode transition. If not specified the mode transition is considered '
                           'to be planned.\n'
                           '    Mode_Transition_Response: enumeration (emergency, planned) applies to (mode '
                           'transition);\n'
                           '\n'
                           '    --- The <b>Resumption_Policy</b> property specifies whether as result of a '
                           'mode transition activation a component that\n'
                           '    --- has modes itself starts in the initial mode or resumes in the current '
                           'mode at the time of its deactivation.  \n'
                           '    Resumption_Policy: enumeration (restart, resume) applies to (thread, thread '
                           'group, process, system, device,\n'
                           '        processor, memory, bus, system, virtual bus, virtual processor, '
                           'subprogram);\n'
                           '\n'
                           '    --- The <b>Active_Thread_Handling_Protocol</b> property specifies the '
                           'protocol to use to handle execution at the time\n'
                           '    --- instant of an actual mode switch. The available choices are '
                           'implementation defined. The default value is abort\n'
                           '    Active_Thread_Handling_Protocol: inherit '
                           'Supported_Active_Thread_Handling_Protocols => abort applies to (thread,\n'
                           '        thread group, process, system);\n'
                           '\n'
                           '    --- The <b>Active_Thread_Queue_Handling_Protocol</b> property specifies the '
                           'protocol to use to handle the content of\n'
                           '    --- any event port or event data port queue of a thread at the time instant '
                           'of an actual mode switch. The available\n'
                           '    --- choices are flush and hold.  Flush empties the queue. Hold keeps the '
                           'content in the queue of the thread being\n'
                           '    --- deactivated until it is reactivated.  The default value is flush.\n'
                           '    Active_Thread_Queue_Handling_Protocol: inherit enumeration (flush, hold) => '
                           'flush applies to (thread, thread group,\n'
                           '        process, system);\n'
                           '\n'
                           '    --- The <b>Deactivation_Policy</b> property specifies whether a process is '
                           'to be unloaded when it is deactivated. If\n'
                           '    --- the policy is unload, then the process is unloaded on deactivate and '
                           'loaded on activate. In the case of threads,\n'
                           '    --- the property indicates whether thread state is saved.<p>\n'
                           '    --- The default is that the process is loaded during startup and is not '
                           'unloaded when deactivated.\n'
                           '    Deactivation_Policy: enumeration (inactive, unload) => inactive applies to '
                           '(thread, process, virtual processor,\n'
                           '        processor);\n'
                           '\n'
                           '    --- This <b>Runtime_Protection</b> property specifies whether a process '
                           'requires runtime enforcement of address space protection. \n'
                           '    ---  If no value is specified the default is <b>true<b>.\n'
                           '    Runtime_Protection: inherit aadlboolean => true applies to (process, system, '
                           'virtual processor);\n'
                           '\n'
                           '    --- The <b>Subprogram_Call_Type</b> property specifies whether the call is '
                           'to be performed synchronous or\n'
                           '    --- semi-synchronous. In case of a semi-synchronous call the user of the '
                           'result is may be suspended until the result\n'
                           '    --- is available. The default is <b>Synchronous</b> if no property value is '
                           'specified.\n'
                           '    Subprogram_Call_Type: enumeration (Synchronous, SemiSynchronous) => '
                           'Synchronous applies to (subprogram);\n'
                           '\n'
                           '    --- The <b>Synchronized_Component</b> property specifies whether a periodic '
                           'thread will be synchronized with \n'
                           '    --- transitions into and out of a mode.  In other words, the thread affects '
                           'the hyperperiod for mode switching of the\n'
                           '    --- property value is true.<p>\n'
                           '    --- The default value is true.\n'
                           '    Synchronized_Component: inherit aadlboolean => true applies to (thread, '
                           'thread group, process, system);\n'
                           '\n'
                           'end Thread_Properties;\n',
 'Timing_Properties.aadl': '--AADLv2.3\n'
                           '--SAE Aerospace Standard AS5506D\n'
                           '--Appendix A: Predeclared Property Sets\n'
                           '\n'
                           '-- @codegen-package org.osate.aadl2.contrib.timing\n'
                           'property set Timing_Properties is\n'
                           '\n'
                           '    --- <b>Activate_Deadline</b> specifies the maximum amount of time allowed '
                           "for the execution of a thread's activation\n"
                           '    --- sequence.  The numeric value of time must be positive.<p>\n'
                           '    --- The property type is <b>Time</b>.  The standard units are ps '
                           '(picoseconds), ns (nanoseconds), us (microseconds),\n'
                           '    --- ms (milliseconds), sec (seconds), min (minutes) and hr (hours).\n'
                           '\n'
                           '    Activate_Deadline: Time\n'
                           '        applies to (thread);\n'
                           '\n'
                           '    --- <b>Activate_Execution_Time</b> specifies the minimum and maximum '
                           'execution time, in the absence of runtime errors,\n'
                           '    --- that a thread will use to execute its activation sequence, i.e., when a '
                           'thread becomes active as part of a mode\n'
                           '    --- switch.  The specified execution time includes all time required to '
                           'execute any service calls that are executed\n'
                           '    --- by a thread, but excludes any time spent by another thread executing '
                           'remote procedure calls in response to a remote\n'
                           '    --- subprogram call made by this thread.\n'
                           '\n'
                           '    Activate_Execution_Time: Time_Range\n'
                           '        applies to (thread);\n'
                           '\n'
                           '    --- The <b>Compute_Deadline</b> specifies the maximum amount of time allowed '
                           'for the execution of a thread�s compute\n'
                           '    --- sequence.  If the property is specified for a subprogram, event port, or '
                           'event data port feature, then this compute\n'
                           '    --- execution time applies to the dispatched thread when the corresponding '
                           'call, event, or event data arrives.   When\n'
                           '    --- specified for a subprogram access feature, the <b>Compute_Deadline</b> '
                           'applies to the thread executing the remote\n'
                           '    --- procedure call in response to the remote subprogram call.  The '
                           '<b>Compute_Deadline</b> specified for a feature must\n'
                           '    --- not exceed the <b>Compute_Deadline</b> of the associated thread. The '
                           'numeric value of time must be positive.<p>\n'
                           '    --- The values specified for this property for a thread are bounds on the '
                           'values specified for specific features.<p>\n'
                           '    --- The <b>Deadline</b> property places a limit on <b>Compute_Deadline</b> '
                           'and <b/>Recover_Deadline: Compute_Deadline +\n'
                           '    --- Recover_Deadline <= Deadline.</b><p>\n'
                           '    --- The property type is <b>Time</b>.  The standard units are ps '
                           '(picoseconds), ns (nanoseconds), us (microseconds), ms\n'
                           '    --- (milliseconds), sec (seconds), min (minutes) and hr (hours).\n'
                           '\n'
                           '    Compute_Deadline: Time\n'
                           '        applies to (thread, device, subprogram, subprogram access, event port, '
                           'event data port);\n'
                           '\n'
                           '    --- The <b>Compute_Execution_Time</b> property specifies the amount of time '
                           'that a thread will execute after a thread\n'
                           '    --- has been dispatched, before that thread begins waiting for another '
                           'dispatch.  If the property is specified for a\n'
                           '    --- subprogram, event port, or event data port feature, then this compute '
                           'execution time applies to the dispatched\n'
                           '    --- thread when the corresponding call, event, or event data initiates a '
                           'dispatch.  When specified for a subprogram\n'
                           '    --- (access) feature, it applies to the thread executing the remote '
                           'procedure call in response to a remote subprogram\n'
                           '    --- call. The <b>Compute_Execution_Time</b> specified for a feature must not '
                           'exceed the <b>Compute_Execution_Time</b>\n'
                           '    --- of the associated thread.<p>\n'
                           '    --- The range expression specifies a minimum and maximum execution time in '
                           'the absence of runtime errors.  The\n'
                           '    --- specified execution time includes all time required to execute any '
                           'service calls that are executed by a thread, but\n'
                           '    --- excludes any time spent by another thread executing remote procedure '
                           'calls in response to a remote subprogram call\n'
                           '    --- made by the thread.<p>\n'
                           '    --- The values specified for this property for a thread are bounds on the '
                           '<b>Compute_Execution_Time</b> values\n'
                           '    --- specified for ports or subprogram access that dispatch execution.\n'
                           '\n'
                           '    Compute_Execution_Time: Time_Range\n'
                           '        applies to (thread, device, subprogram, event port, event data port);\n'
                           '\n'
                           '    --- The <b>Client_Subprogram_Execution_Time</b> property specifies the '
                           'length of time it takes to execute the client\n'
                           '    --- portion of a remote subprogram call.<p>\n'
                           '    --- The property type is <b>Time_Range</b>.  The standard units are ns '
                           '(nanoseconds), us (microseconds), ms\n'
                           '    --- (milliseconds), sec (seconds), min (minutes) and hr (hours).  The '
                           'numeric value must be a positive number.\n'
                           '\n'
                           '    Client_Subprogram_Execution_Time: Time_Range\n'
                           '        applies to (subprogram);\n'
                           '\n'
                           '    --- The <b>Deactivate_Deadline</b> property specifies the maximum amount of '
                           'time allowed for the execution of a\n'
                           "    --- thread's deactivation sequence.  The numeric value of time must be "
                           'positive.<p>\n'
                           '    --- The property type is <b>Time</b>.  The standard units are ps '
                           '(picoseconds), ns (nanoseconds), us (microseconds),\n'
                           '    --- ms (milliseconds), sec (seconds), min (minutes) and hr (hours).\n'
                           '\n'
                           '    Deactivate_Deadline: Time\n'
                           '        applies to (thread);\n'
                           '\n'
                           '    --- The <b>Deactivate_Execution_Time</b> property specifies the amount of '
                           'time that a thread will execute its\n'
                           '    --- deactivation sequence, i.e., when the thread is deactivated as part of a '
                           'mode switch.<p>\n'
                           '    --- The range expression specifies a minimum and maximum execution time in '
                           'the absence of runtime errors.  The\n'
                           '    --- specified execution time includes all time required to execute any '
                           'service calls that are executed by a thread, but\n'
                           '    --- excludes any time spent by another thread executing remote procedure '
                           'calls in response to a remote subprogram call\n'
                           '    --- made by this thread.\n'
                           '\n'
                           '    Deactivate_Execution_Time: Time_Range\n'
                           '        applies to (thread);\n'
                           '\n'
                           '    --- The <b>Deadline</b> property specifies the maximum amount of time '
                           'allowed between a thread dispatch and the time\n'
                           '    --- that thread begins waiting for another dispatch.  Its numeric value must '
                           'be positive.<p>\n'
                           '    --- The <b>Deadline</b> property places a limit on <b>Compute_Deadline</b> '
                           'and <b>Recover_Deadline</b>:\n'
                           '    --- <b>Compute_Deadline</b> + <b>Recover_Deadline<b> <= <b>Deadline</b><p>\n'
                           '    --- The Deadline property may not be specified for threads with background '
                           'dispatch protocol.\n'
                           '\n'
                           '    Deadline: inherit Time => Period\n'
                           '        applies to (thread, thread group, process, system, device, virtual '
                           'processor);\n'
                           '\n'
                           '    --- This property specifies the time of the first dispatch request.\n'
                           '\n'
                           '    First_Dispatch_Time: inherit Time\n'
                           '        applies to (thread, thread group);\n'
                           '\n'
                           '    --- The <b>Dispatch_Jitter</b> property specifies a maximum bound on the '
                           'lateness of a thread dispatching. In the case\n'
                           '    --- of a periodic thread for instance, the thread is supposed to be '
                           'dispatched according to a fixed delay called the\n'
                           '    --- period. However, for many reasons, a periodic thread dispatching event '
                           'can be delayed. The <b>Dispatch_Jitter</b>\n'
                           '    --- property can be used to specify such a delay. The <b>Dispatch_Jitter</b> '
                           'property can be specified on any thread\n'
                           '    --- which can be dispatched several times (e.g.,. <b>Periodic</b>, '
                           '<b>Sporadic</b>).\n'
                           '\n'
                           '    Dispatch_Jitter: inherit Time\n'
                           '        applies to (thread, thread group);\n'
                           '\n'
                           '    --- The <b>Dispatch_Offset</b> property specifies a dispatch time offset for '
                           'a thread.  The offset indicates the amount\n'
                           '    --- of clock time by which the dispatch of a thread is offset relative to '
                           'its period.  This property applies only to\n'
                           '    --- periodic threads.\n'
                           '\n'
                           '    Dispatch_Offset: inherit Time\n'
                           '        applies to (thread);\n'
                           '\n'
                           '    --- The <b>Execution_Time</> property specifies the amount of execution time '
                           'allocated to a virtual processor by the\n'
                           '    --- processor it is bound to.  This is the amount of execution time the '
                           'virtual processor can make available to threads\n'
                           '    --- or virtual processors it schedules. It is the equivalent of the '
                           '<b>compute_execution_time</b> for a thread.\n'
                           '\n'
                           '    Execution_Time: Time\n'
                           '        applies to (virtual processor);\n'
                           '\n'
                           '    --- The <b>Finalize_Deadline</b> property specifies the maximum amount of '
                           'time allowed for the execution of a thread�s\n'
                           '    --- finalization sequence.  The numeric value of time must be positive.<p>\n'
                           '    --- The property type is <b>Time</b>.  The standard units are ps '
                           '(picoseconds), ns (nanoseconds), us (microseconds),\n'
                           '    --- ms (milliseconds), sec (seconds), min (minutes) and hr (hours).\n'
                           '\n'
                           '    Finalize_Deadline: Time\n'
                           '        applies to (thread);\n'
                           '\n'
                           '    --- The <b>Finalize_Execution_Time</b> property specifies the amount of time '
                           'that a thread will execute its\n'
                           '    --- finalization sequence. <p>\n'
                           '    --- The range expression specifies a minimum and maximum execution time in '
                           'the absence of runtime errors.  The\n'
                           '    --- specified execution time includes all time required to execute any '
                           'service calls that are executed by a thread, but\n'
                           '    --- excludes any time spent by another thread executing remote procedure '
                           'calls in response to a remote subprogram call\n'
                           '    --- made by this thread.\n'
                           '\n'
                           '    Finalize_Execution_Time: Time_Range\n'
                           '        applies to (thread);\n'
                           '\n'
                           '     --- The <b>Initialize_Deadline</b>; property specifies the maximum amount '
                           'of time allowed between the time a thread\n'
                           '     --- executes its initialization sequence and the time that thread begins '
                           'waiting for a dispatch.  The numeric value of\n'
                           '     --- time must be positive.<p>\n'
                           '     --- The property type is <b>Time</b>.  The standard units are ps '
                           '(picoseconds), ns (nanoseconds), us (microseconds), ms\n'
                           '     --- (milliseconds), sec (seconds), min (minutes) and hr (hours).\n'
                           '\n'
                           '    Initialize_Deadline: Time\n'
                           '        applies to (thread);\n'
                           '\n'
                           '    --- The <b>Initialize_Execution_Time</b> property specifies the amount of '
                           'time that a thread will execute its\n'
                           '    --- initialization sequence.<p>\n'
                           '    --- The range expression specifies a minimum and maximum execution time in '
                           'the absence of runtime errors.  The\n'
                           '    --- specified execution time includes all time required to execute any '
                           'service calls that are executed by a thread, but\n'
                           '    --- excludes any time spent by another thread executing remote procedure '
                           'calls in response to a remote subprogram call\n'
                           '    --- made by this thread.\n'
                           '\n'
                           '    Initialize_Execution_Time: Time_Range\n'
                           '        applies to (thread);\n'
                           '\n'
                           '    --- The <b>Load_Deadline</b> property specifies the maximum amount of '
                           'elapsed time allowed between the time the process\n'
                           '    --- begins and completes loading.  Its numeric value must be positive.<p>\n'
                           '    --- The property type is <b>Time</b>.  The standard units are ns '
                           '(nanoseconds), us (microseconds), ms (milliseconds),\n'
                           '    --- sec (seconds), min (minutes) and hr (hours).\n'
                           '\n'
                           '    Load_Deadline: Time\n'
                           '        applies to (process, system);\n'
                           '\n'
                           '    --- The <b>Load_Time</b> property specifies the amount of execution time '
                           'that it will take to load the binary image\n'
                           '    --- associated with a process.  The numeric value of time must be '
                           'positive.<p>\n'
                           '    --- When applied to a system, the property specifies the amount of time it '
                           'takes to load the binary image of data\n'
                           '    --- components declared within the system implementation and shared across '
                           'processes (and their address spaces).<p>\n'
                           '    --- The range expression specifies a minimum and maximum load time in the '
                           'absence of runtime errors.\n'
                           '\n'
                           '    Load_Time: Time_Range\n'
                           '        applies to (process, system);\n'
                           '\n'
                           '    --- This property specifies the capacity/speed of a processor in terms of '
                           'instructions per time unit. The ratio of\n'
                           '    --- processor capacity between processors is used to determine a scaling '
                           'factor for adjusting execution times to\n'
                           '    --- specific processors\n'
                           '\n'
                           '\n'
                           '    Processor_Capacity: aadlreal units Processor_Speed_Units\n'
                           '        applies to (processor, virtual processor, system);\n'
                           '\n'
                           '    --- The <b>Period</b> property specifies the time interval between '
                           'successive dispatches of a thread whose scheduling\n'
                           '    --- protocol is periodic, or the minimum interval between successive '
                           'dispatches of a thread whose scheduling protocol\n'
                           '    --- is sporadic.<p>\n'
                           '    --- The property type is <b>Time</b>.  The standard units are ns '
                           '(nanoseconds), us (microseconds), ms (milliseconds),\n'
                           '    --- sec (seconds), min (minutes) and hr (hours).  The numeric value must be '
                           'a single positive number.<p>\n'
                           '    --- A <b>Period</b> property association is only allowed if the thread '
                           'scheduling protocol is either periodic or\n'
                           '    --- sporadic, timed or hybrid.\n'
                           '\n'
                           '    Period: inherit Time\n'
                           '        applies to (thread, thread group, process, system, device, virtual '
                           'processor, virtual bus, bus);\n'
                           '\n'
                           '    --- <b>Recover_Deadline</b> specifies the maximum amount of time allowed '
                           'between the time when a detected error occurs\n'
                           '    --- and the time a thread begins waiting for another dispatch.  Its numeric '
                           'value must be positive.<p>\n'
                           '    --- The <b>Recover_Deadline</b> property may not be specified for threads '
                           'with background dispatch protocol.<p>\n'
                           '    --- The <b>Recover_Deadline</b> must not be greater than the specified '
                           'period for the thread, if any.<p>\n'
                           '    --- The <b>Deadline</b> property places a limit on <b>Compute_Deadline</b> '
                           'and <b>Recover_Deadline:\n'
                           '    --- Compute_Deadline + Recover_Deadline <= Deadline</b>.<p>\n'
                           '    --- The property type is <b>Time</b>.  The standard units are ps '
                           '(picoseconds), ns (nanoseconds), us (microseconds),\n'
                           '    --- ms (milliseconds), sec (seconds), min (minutes) and hr (hours).\n'
                           '\n'
                           '    Recover_Deadline: Time\n'
                           '        applies to (thread);\n'
                           '\n'
                           '    --- The <b>Recover_Execution_Time</b> property specifies the amount of time '
                           'that a thread will execute after an error\n'
                           '    --- has occurred, before it begins waiting for another dispatch.<p>\n'
                           '    --- The range expression specifies a minimum and maximum execution time in '
                           'the absence of runtime errors.  The\n'
                           '    --- specified execution time includes all time required to execute any '
                           'service calls that are executed by a thread, but\n'
                           '    --- excludes any time spent by another thread executing remote procedure '
                           'calls in response to a remote subprogram call\n'
                           '    --- made by this thread.\n'
                           '\n'
                           '    Recover_Execution_Time: Time_Range\n'
                           '        applies to (thread);\n'
                           '\n'
                           '    --- This property identifies the reference time being used by the component '
                           'the property applies to. The reference time is\n'
                           '    --- represented by an instance of a processor, device, bus, or system '
                           'component type.\n'
                           '\n'
                           '    Reference_Time: inherit reference (device, processor, bus, system, '
                           'abstract)\n'
                           '        applies to (device, processor, bus, system, memory, virtual bus, virtual '
                           'processor);\n'
                           '\n'
                           '    --- The <b>Startup_Deadline</b> property specifies the deadline for '
                           'processor, virtual processor, process, and system\n'
                           '    --- initialization.<p>\n'
                           '    --- The property type is <b.Time</b>.  The standard units are ps '
                           '(picoseconds), ns (nanoseconds), us (microseconds), ms\n'
                           '    --- (milliseconds), sec (seconds), min (minutes) and hr (hours).  The '
                           'numeric value must be a single positive number.\n'
                           '\n'
                           '    Startup_Deadline: Time\n'
                           '        applies to (processor, virtual processor, process, system);\n'
                           '\n'
                           '    --- The <b>Startup_Execution_Time</b> property specifies the execution time '
                           'for initialization of a virtual processor or\n'
                           '    --- process.  Initialization time for threads is accounted for through its '
                           'initialize entry point.<p>\n'
                           '    --- The property type is <b>Time</b>.  The standard units are ps '
                           '(picoseconds), ns (nanoseconds), us (microseconds), ms\n'
                           '    --- (milliseconds), sec (seconds), min (minutes) and hr (hours).  The '
                           'numeric value must be a single positive number.\n'
                           '\n'
                           '    Startup_Execution_Time: Time_Range\n'
                           '        applies to (virtual processor, processor, process, system);\n'
                           '\n'
                           '    --- The <b>Clock_Jitter property</b> specifies a time unit value that gives '
                           'the maximum time between the start of clock\n'
                           '    --- interrupt handling on any two processors in a multi-processor '
                           'system.<p>\n'
                           '    --- The property type is <b>Time</b>.  The standard units are ns '
                           '(nanoseconds), us (microseconds), ms (milliseconds),\n'
                           '    --- sec (seconds), min (minutes) and hr (hours).  The numeric value must be '
                           'a positive number.\n'
                           '\n'
                           '    Clock_Jitter: Time\n'
                           '        applies to (processor, device, system);\n'
                           '\n'
                           '    --- The <b>Clock_Period</b> property specifies a time unit value that gives '
                           'the time interval between two clock\n'
                           '    --- interrupts.<p>\n'
                           '    --- The property type is <b>Time</b>.  The standard units are ns '
                           '(nanoseconds), us (microseconds), ms (milliseconds),\n'
                           '    --- sec (seconds), min (minutes) and hr (hours).  The numeric value must be '
                           'a positive number.\n'
                           '\n'
                           '    Clock_Period: Time\n'
                           '        applies to (processor, device, system);\n'
                           '\n'
                           '    --- The <b>Clock_Period_Range</b> property specifies a time range value that '
                           'represents the minimum and maximum\n'
                           '    --- value assignable to the <b>Clock_Period</b> property.\n'
                           '\n'
                           '    Clock_Period_Range: Time_Range\n'
                           '        applies to (processor, device, system);\n'
                           '\n'
                           '    --- The <b>Process_Swap_Execution_Time</b> property specifies the amount of '
                           'execution time necessary to perform a\n'
                           '    --- context swap between two threads contained in different processes.<p>\n'
                           '    --- The range expression specifies a minimum and maximum swap time in the '
                           'absence of runtime errors.\n'
                           '\n'
                           '    Process_Swap_Execution_Time: Time_Range\n'
                           '        applies to (processor, virtual processor, system);\n'
                           '\n'
                           '    --- The <b>Reference_Processor</b> property specifies the processor based on '
                           'which the execution time is specified.\n'
                           '    --- When code is bound to a different processor type, the '
                           '>b>Processor_Capacity</b> of that processor is used to\n'
                           '    --- determine the execution, unless a binding specific execution time value '
                           'is associated.\n'
                           '\n'
                           '    Reference_Processor: inherit classifier (processor)\n'
                           '        applies to (subprogram, subprogram group, thread, thread group, process, '
                           'device, system);\n'
                           '\n'
                           '    --- This property specifies the quantum of a given processor. The quantum is '
                           'a maximum bound on the time a thread can\n'
                           '    --- hold the processor without being preempted. A quantum is typically used '
                           'in time sharing scheduling and in\n'
                           '    --- POSIX 1003.1b scheduling (with the SCHED_RR policy). The quantum can be '
                           'used with any user-defined schedulers. If\n'
                           '    --- the quantum is not specified for a given processor, the quantum has a '
                           'positive infinitesimal value.\n'
                           '\n'
                           '    Scheduler_Quantum: inherit Time\n'
                           '        applies to (processor);\n'
                           '\n'
                           '    --- The <b>Thread_Swap_Execution_Time</b> property specifies the amount of '
                           'execution time necessary for performing a\n'
                           '    --- context swap between two threads contained in the same process.<p>\n'
                           '    --- The range expression specifies a minimum and maximum swap time in the '
                           'absence of runtime errors.\n'
                           '\n'
                           '    Thread_Swap_Execution_Time: Time_Range\n'
                           '        applies to (processor, system, virtual processor);\n'
                           '\n'
                           '    --- The <b>Frame_Period</b> property specifies the time period of a major '
                           'frame in a static scheduling protocol, such as\n'
                           '    --- a cyclic executive.\n'
                           '\n'
                           '    Frame_Period: Time\n'
                           '        applies to (processor, virtual processor);\n'
                           '\n'
                           '    --- The <b>Slot_Time</b> property specifies the time period of a slot in '
                           'major frame in a static scheduling protocol,\n'
                           '    --- such as a cyclic executive, if the protocol uses fixed slot times.\n'
                           '\n'
                           '    Slot_Time: Time\n'
                           '        applies to (processor, virtual processor);\n'
                           '\n'
                           'end Timing_Properties;\n'}


def _normalized_builtin_sources(sources):
    """Adapt three OSATE resources to the normative numeric/unit separator.

    AS5506D 15.2(4) requires a separator between a number and an identifier.
    These substitutions apply only to the pinned dependency literals above;
    candidate/user text is never rewritten. Original hashes and every source
    edit remain available to callers as provenance.
    """
    pattern = re.compile(r'--[^\r\n]*|"(?:""|[^"])*"|'
                         r'(?<![A-Za-z0-9_#])([0-9]+(?:\.[0-9]+)?)(ps|ns|us|ms|sec|min|hr|Bytes)(?![A-Za-z0-9_])', re.I)
    normalized, provenance = {}, {}
    for name, source in sources.items():
        edits = []
        def replace(match):
            if match.group(1) is None:
                return match.group()
            replacement = match.group(1) + ' ' + match.group(2)
            edits.append({'line': source.count('\n', 0, match.start()) + 1,
                          'before': match.group(), 'after': replacement})
            return replacement
        normalized[name] = pattern.sub(replace, source)
        provenance[name] = {'revision': OSATE_REVISION,
                            'original_sha256': hashlib.sha256(source.encode('utf-8')).hexdigest(),
                            'normalization': 'AS5506D 15.2(4) numeric/unit separator', 'replacements': tuple(edits)}
    return normalized, provenance


BUILTIN_SOURCES, BUILTIN_SOURCE_PROVENANCE = _normalized_builtin_sources(BUILTIN_SOURCES)


# The category tables are the AS5506D category-clause restrictions summarized
# by informative Appendix C.1. They do not infer completeness of a system
# instance: instantiation and its consistency checks remain a distinct phase.
CATEGORIES = ('abstract', 'data', 'subprogram', 'subprogram group', 'thread',
              'thread group', 'process', 'processor', 'virtual processor',
              'memory', 'bus', 'virtual bus', 'device', 'system')
CONTAINMENT = {
    'abstract': CATEGORIES,
    'data': ('data', 'subprogram', 'abstract'),
    'subprogram': ('data', 'subprogram', 'abstract'),
    'subprogram group': ('data', 'subprogram', 'subprogram group', 'abstract'),
    'thread': ('data', 'subprogram', 'subprogram group', 'abstract'),
    'thread group': ('data', 'subprogram', 'subprogram group', 'thread', 'thread group', 'abstract'),
    'process': ('data', 'subprogram', 'subprogram group', 'thread', 'thread group', 'abstract'),
    'processor': ('memory', 'bus', 'virtual processor', 'virtual bus', 'abstract'),
    'virtual processor': ('virtual processor', 'virtual bus', 'abstract'),
    'memory': ('memory', 'bus', 'virtual bus', 'abstract'),
    'bus': ('virtual bus', 'abstract'), 'virtual bus': ('virtual bus', 'abstract'),
    'device': ('bus', 'virtual bus', 'data', 'abstract'),
    'system': ('data', 'subprogram', 'subprogram group', 'process', 'processor',
               'virtual processor', 'memory', 'bus', 'virtual bus', 'device', 'system', 'abstract'),
}
_SOFTWARE = {'thread', 'thread group', 'process'}
_PLATFORM = {'processor', 'virtual processor', 'memory', 'bus', 'virtual bus', 'device'}
PORT_KINDS = ('data port', 'event port', 'event data port')
DIRECTIONS = ('in', 'out', 'in out')
PORT_DESTINATIONS = {'event port': {'event port'},
                     'data port': set(PORT_KINDS), 'event data port': set(PORT_KINDS)}
_LEX = re.compile(r'--[^\r\n]*|"(?:""|[^"])*"|[A-Za-z\u00c0-\u00d6\u00d8-\u00f6\u00f8-\u00ff](?:_?[A-Za-z\u00c0-\u00d6\u00d8-\u00f6\u00f8-\u00ff0-9])*|'
                  r'[0-9][0-9_]*#[0-9A-Fa-f_]+(?:\.[0-9A-Fa-f_]+)?#(?:[eE][+-]?[0-9_]+)?|'
                  r'[0-9][0-9_]*(?:\.[0-9][0-9_]*)?(?:[eE][+-]?[0-9_]+)?|'
                  r'<->|\+=>|=>|->|::|\.\.|[^\s]', re.S)
_IDENT = re.compile(r'[A-Za-z\u00c0-\u00d6\u00d8-\u00f6\u00f8-\u00ff](?:_?[A-Za-z\u00c0-\u00d6\u00d8-\u00f6\u00f8-\u00ff0-9])*')


@lru_cache(maxsize=8192)
def _token_tuple(text):
    """Keep strings opaque; a comment-looking sequence inside a string is data."""
    # Latin-1 lowercase is one-to-one. Unicode casefold would incorrectly
    # identify the distinct AADL names Straße and Strasse with one another.
    return tuple(m.group() if m.group().startswith('"') else m.group().lower()
                 for m in _LEX.finditer(text) if not m.group().startswith('--'))


def _tokens(text):
    return list(_token_tuple(text))


@lru_cache(maxsize=8192)
def _text(node):
    return ' '.join(_tokens(node.text)) if node is not None else ''


@lru_cache(maxsize=8192)
def _name(node):
    return ''.join(_tokens(node.text)) if node is not None else ''


def _walk(node):
    yield node
    for child in getattr(node, 'children', ()):
        yield from _walk(child)


# Node-valued queries deliberately do not use the global scalar LRU caches.
# Equal trees from separate probes have different node identities: returning an
# old subtree here would bypass the evaluator's snapshot/parent ownership rules.
def _direct(node, production):
    return tuple(c for c in getattr(node, 'children', ()) if c.production == production)


def _find(node, production):
    return tuple(c for c in _walk(node) if c.production == production)


def _first(node, *productions, recursive=False):
    candidates = _walk(node) if recursive else getattr(node, 'children', ())
    return next((c for c in candidates if c.production in productions), None)


def _field(node, *productions, recursive=False):
    return _name(_first(node, *productions, recursive=recursive))


def _bound(node, field):
    """Read an actual production binding; a missing binding is a rule defect."""
    if field not in node.bindings:
        raise RuleDefinitionError(f'{node.production} has no semantic field binding {field}')
    return next(iter(node.field(field)), None)


def _section_items(node, section, item):
    containers = (node,) + _direct(node, 'type_body') + _direct(node, 'implementation_body')
    return tuple(child for container in containers for part in _direct(container, section)
                 for child in _find(part, item))


def _sections(node, section):
    containers = (node,) + _direct(node, 'type_body') + _direct(node, 'implementation_body')
    return tuple(part for container in containers for part in _direct(container, section))


def _complete(node):
    return node is not None and getattr(node, 'complete', True)


def _number(text):
    """AADL decimal/based numerals are values, never Python expressions."""
    text = text.replace('_', '').lower()
    sign = -1 if text.startswith('-') else 1
    text = text.lstrip('+-')
    if '#' in text:
        base, digits, rest = text.split('#')
        exponent = int(rest[1:]) if rest else 0
        whole, _, fractional = digits.partition('.')
        value = Decimal(int(whole or '0', int(base)))
        value += sum((Decimal(int(char, int(base))) / Decimal(int(base)) ** i
                      for i, char in enumerate(fractional, 1)), Decimal(0))
        return sign * value * Decimal(int(base)) ** exponent
    return sign * Decimal(text)


@dataclass
class _Package:
    name: str
    node: object
    imports: set = field(default_factory=set)
    renames: dict = field(default_factory=dict)
    import_all: set = field(default_factory=set)
    sections: dict = field(default_factory=dict)
    parts: list = field(default_factory=list)


@dataclass
class _Declaration:
    key: str
    name: str
    package: _Package
    category: str
    implementation: bool
    node: object
    private: bool = False
    extension: str = ''
    features: dict = field(default_factory=dict)
    children: dict = field(default_factory=dict)
    connections: dict = field(default_factory=dict)
    modes: dict = field(default_factory=dict)
    transitions: dict = field(default_factory=dict)
    flows: dict = field(default_factory=dict)
    end_to_end: dict = field(default_factory=dict)
    prototypes: dict = field(default_factory=dict)
    calls: dict = field(default_factory=dict)
    internal: dict = field(default_factory=dict)
    processor: dict = field(default_factory=dict)
    public_part: object = None


@dataclass
class _Feature:
    name: str
    kind: str
    direction: str
    classifier: str
    node: object
    refined: bool = False
    inverse: bool = False


@dataclass
class _Child:
    name: str
    category: str
    classifier: str
    node: object
    refined: bool = False


def normalize_plan(plan):
    """The planning wire format belongs to this rule package, not the runtime."""
    if isinstance(plan, list):
        result = copy.deepcopy(plan)
    elif isinstance(plan, dict) and set(plan) == {'component_types', 'implementations'}:
        if not all(isinstance(plan[key], list) for key in plan):
            raise ValueError('component_types and implementations must be arrays')
        result = []
        for item in plan['component_types']:
            if not isinstance(item, dict):
                raise ValueError('Each planned type must be an object')
            result.append(dict(item, kind='type'))
        for item in plan['implementations']:
            if not isinstance(item, dict):
                raise ValueError('Each planned implementation must be an object')
            result.append(dict(item, kind='implementation', ports=[]))
    else:
        raise ValueError('Plan must be a declaration array or component_types/implementations object')
    _validate_plan(result)
    return result


def _validate_plan(plan):
    if plan is None:
        return None
    if isinstance(plan, dict) and set(plan) == {'component_types', 'implementations'}:
        plan = normalize_plan(plan)
    if not isinstance(plan, list):
        raise ValueError('Declaration plan must be an array')
    symbols = {}
    for item in plan:
        if not isinstance(item, dict) or set(item) != {'name', 'category', 'kind', 'ports'}:
            raise ValueError('Planned declarations require name, category, kind and ports')
        if any(not isinstance(item[k], str) for k in ('name', 'category', 'kind')) or not isinstance(item['ports'], list):
            raise ValueError('Plan name/category/kind must be strings and ports must be an array')
        name, category, kind = item['name'].lower(), item['category'].lower(), item['kind']
        if category not in CATEGORIES or kind not in {'type', 'implementation'}:
            raise ValueError(f'Invalid planned category or kind: {name}')
        local = name.rsplit('::', 1)[-1]
        if len(local.split('.')) != (2 if kind == 'implementation' else 1) or any(
                not _IDENT.fullmatch(part) or part in RESERVED for part in re.split(r'::|\.', name)):
            raise ValueError(f'Invalid planned classifier name: {name}')
        if name in symbols:
            raise ValueError(f'Duplicate planned classifier: {name}')
        ports = {}
        for port in item['ports']:
            if not isinstance(port, dict) or set(port) != {'name', 'direction', 'kind', 'classifier'} or any(
                    not isinstance(v, str) for v in port.values()):
                raise ValueError(f'Invalid planned interface structure in {name}')
            pname = port['name'].lower()
            pkind = port['kind'].lower()
            pkind = pkind if pkind.endswith(' port') else pkind + ' port'
            if pname in ports or not _IDENT.fullmatch(pname) or pname in RESERVED:
                raise ValueError(f'Invalid or duplicate planned interface: {pname}')
            if pkind not in PORT_KINDS or port['direction'] not in DIRECTIONS:
                raise ValueError(f'Invalid planned port signature: {pname}')
            if pkind == 'event port' and port['classifier']:
                raise ValueError('An event port cannot have a data classifier')
            ports[pname] = {**port, 'name': pname, 'kind': pkind, 'classifier': port['classifier'].lower()}
        if ports and kind == 'implementation':
            raise ValueError('Planned implementations inherit interfaces from their type')
        symbols[name] = {'name': name, 'category': category, 'kind': kind, 'ports': ports}
    for name, item in symbols.items():
        if item['kind'] == 'implementation':
            base = symbols.get(name.rsplit('.', 1)[0])
            if not base or base['kind'] != 'type' or base['category'] != item['category']:
                raise ValueError(f'Planned implementation has no matching planned type: {name}')
        for port in item['ports'].values():
            if port['classifier']:
                target = symbols.get(port['classifier'])
                if target is None or target['category'] != 'data':
                    raise ValueError(f'Planned port classifier is not a planned data declaration: {port["classifier"]}')
    return symbols


def _repair_code_view(text):
    """Hide non-code without changing source offsets or line numbers.

    Repair hints are not a second parser. They deliberately ignore strings,
    comments and annex bodies, whose words must not be treated as AADL clauses.
    The same masking also handles truncated quoted/annex text conservatively.
    """
    output, index = list(text), 0
    while index < len(text):
        start = index
        if text.startswith('--', index):
            while index < len(text) and text[index] not in '\r\n':
                index += 1
        elif text.startswith('{**', index):
            end = text.find('**}', index + 3)
            index = len(text) if end < 0 else end + 3
        elif text[index] == '"':
            index += 1
            while index < len(text):
                if text.startswith('""', index):
                    index += 2  # AADL escapes a quote by doubling it.
                elif text[index] == '"':
                    index += 1
                    break
                else:
                    index += 1
        else:
            index += 1
            continue
        for position in range(start, index):
            if text[position] not in '\r\n':
                output[position] = ' '
    return ''.join(output)


def _repair_data_patterns(source):
    """Recognize two narrow malformed constructs, without judging a project.

    This recognizer only supplies advice after a real error at the same source
    line. Keeping recognition separate also lets later feedback report whether
    a construct was observed again, without calling its disappearance a fix.
    """
    identifier = _IDENT.pattern
    namespace = rf'{identifier}(?:\s*::\s*{identifier})*'
    classifier = rf'{namespace}(?:\s*\.\s*{identifier})?'
    patterns = (
        ('aadl.data_port_classifier_keyword', re.compile(
            rf'^\s*{identifier}\s*:\s*(?:in\s+out|in|out)\s+(?:event\s+)?data\s+port\s+type\s+{classifier}\s*;\s*$',
            re.IGNORECASE),
         'A data or event-data port names its classifier directly after port; type is not an intervening keyword.',
         'Remove the extra type keyword between data port and the classifier. '
         'Use the fragment `p: out data port Library::Payload;`, preserving this port\'s actual name, '
         'direction, kind and classifier. Regenerate the complete project.'),
        ('aadl.data_declaration_keyword', re.compile(
            rf'^\s*data\s+type\s+{identifier}\s*$', re.IGNORECASE),
         'A data component type declaration begins with data followed by its defining name.',
         'Remove the extra type keyword from this declaration header. Use the fragment '
         '`data Payload ... end Payload;`, preserving the actual defining and closing name. '
         'The ellipsis denotes the existing body, not literal AADL source. Regenerate the complete project.'),
    )
    original = source['text'].splitlines()
    for number, line in enumerate(_repair_code_view(source['text']).splitlines(), 1):
        for pattern_id, pattern, explanation, suggestion in patterns:
            if pattern.fullmatch(line):
                yield {'id': pattern_id, 'source': source['name'], 'line': number,
                       'observed': original[number - 1].strip(),
                       'explanation': explanation, 'suggestion': suggestion}


def _repair_error_lines(source, diagnostics):
    """Only located errors in this file support the new construct advice.

    Flat messages and warnings cannot prove where a malformed spelling occurs.
    Preserve a relative source's full path suffix instead of matching arbitrary
    basenames, which could attach another file's diagnostic to this source.
    """
    name = source['name'].replace('\\', '/').casefold()
    for diagnostic in diagnostics:
        if (not isinstance(diagnostic, dict) or not isinstance(diagnostic.get('severity'), str)
                or diagnostic['severity'].lower() != 'error'):
            continue
        path = diagnostic.get('source', diagnostic.get('path', diagnostic.get('file')))
        line = diagnostic.get('line')
        if isinstance(path, str) and type(line) is int and line > 0:
            path = path.replace('\\', '/').casefold()
            if path == name or path.endswith('/' + name):
                yield line


def reobserve_repair_focus(focus, sources):
    """Re-observe a supported spelling in its source, not a repaired obligation.

    Lines may move between attempts, so this is deliberately source-wide. A
    false result means only that this recognizable construct was not seen; it
    does not establish syntax, semantics, or requirement satisfaction. Missing
    sources and unsupported patterns remain unknown rather than false.
    """
    pattern_id, name = focus.get('pattern_id'), focus.get('source')
    if pattern_id not in {'aadl.data_port_classifier_keyword', 'aadl.data_declaration_keyword'}:
        return None
    text = sources.get(name)
    if not isinstance(text, str):
        return None
    return any(item['id'] == pattern_id for item in _repair_data_patterns({'name': name, 'text': text}))


def repair_advice(sources, diagnostics, declarations=None):
    """Return located AADL advice, never rewritten source or decoding rules.

    The validators retain their original diagnostics. These narrowly recognized
    source patterns explain a likely cause when parser recovery instead reports
    a misleading missing ``end`` or mismatched package name. No package names
    are hard-coded, and suggestions remain advisory for the same model to apply.
    """
    identifier = _IDENT.pattern
    namespace = rf'{identifier}(?:\s*::\s*{identifier})*'
    names = rf'{namespace}(?:\s*,\s*{namespace})*'
    import_word = '[iI][mM][pP][oO][rR][tT]'
    clause = re.compile(rf'^\s*{import_word}\s+({names})\s*;?\s*$')
    bare = re.compile(rf'^\s*({namespace})\s*;?\s*$')
    package_header = re.compile(rf'\b[pP][aA][cC][kK][aA][gG][eE]\s+({namespace})')
    known, views = set(), []
    for declaration in declarations or ():
        name = declaration.get('name', '')
        if '::' in name:
            known.add(name.rsplit('::', 1)[0].lower())
    for source in sources:
        code = _repair_code_view(source['text'])
        views.append(code)
        for match in re.finditer(rf'{identifier}(?:\s*::\s*{identifier})+', code):
            # A qualified classifier/property use supplies its namespace; a
            # namespaced package/with declaration itself does not supply its
            # shorter parent namespace as a separate dependency.
            before = code[code.rfind('\n', 0, match.start()) + 1:match.start()].strip().lower()
            if before not in {'package', 'with'}:
                known.add(re.sub(r'\s+', '', match.group()).rsplit('::', 1)[0].lower())
        for match in package_header.finditer(code):
            known.add(re.sub(r'\s+', '', match.group(1)).lower())

    advice = []
    for source, code in zip(sources, views):
        error_lines = set(_repair_error_lines(source, diagnostics))
        advice.extend(item for item in _repair_data_patterns(source) if item['line'] in error_lines)
        original = source['text'].splitlines()
        offset = 0
        for line_number, code_line in enumerate(code.splitlines(keepends=True), 1):
            line = code_line.rstrip('\r\n')
            match = clause.fullmatch(line)
            # The broad identifier pattern also spells reserved words. They
            # cannot be namespace targets: in particular, ``import renames``
            # can be the start of a legal alias named import, with its category
            # and target on later lines or after comments. Leave it untouched.
            if match and any(part.lower() in RESERVED for part in re.findall(identifier, match.group(1))):
                match = None
            if match:
                targets = ', '.join(re.sub(r'\s+', '', item) for item in match.group(1).split(','))
                advice.append({'id': 'aadl.with_clause_keyword', 'source': source['name'],
                    'line': line_number, 'observed': original[line_number - 1].strip(),
                    'explanation': 'AADL makes a namespace visible with the keyword with. '
                        'This standalone import statement is not an AADL visibility clause; '
                        'a later missing-end or ending-name diagnostic can be parser recovery from this line.',
                    'suggestion': f'Replace this statement with `with {targets};` in the package visibility section '
                        'or property-set import section, before declarations. Preserve the package and component names; '
                        'regenerate the complete project.'})
            elif not source['complete']:
                match = bare.fullmatch(line)
                spelling = re.sub(r'\s+', '', match.group(1)) if match else ''
                target = spelling[6:] if spelling[:6].lower() == 'import' else ''
                prefix = code[:offset]
                # Only explain this spelling immediately in a visibility
                # section. A component/feature actually named importLibrary,
                # or an alias followed by renames on the next line, is legal.
                sections = list(re.finditer(r'\b(?:[pP][uU][bB][lL][iI][cC]|[pP][rR][iI][vV][aA][tT][eE])\b', prefix))
                visibility = prefix[sections[-1].end():] if sections else None
                follows_visibility = visibility is not None and re.fullmatch(
                    rf'\s*(?:[wW][iI][tT][hH]\s+{names}\s*;\s*)*', visibility) is not None
                remaining = code[offset + len(code_line):].lstrip()
                alias_follows = re.match(r'[rR][eE][nN][aA][mM][eE][sS]\b', remaining) is not None
                own = package_header.search(prefix)
                own_name = re.sub(r'\s+', '', own.group(1)).lower() if own else ''
                if (target and target.lower() in known and target.lower() != own_name
                        and follows_visibility and not alias_follows):
                    advice.append({'id': 'aadl.unfinished_visibility_identifier', 'source': source['name'],
                        'line': line_number, 'observed': original[line_number - 1].strip(),
                        'explanation': f'This line is an unfinished identifier, not a with clause. '
                            f'The source names {target} as a namespace. Repeating comments cannot complete the declaration.',
                        'suggestion': f'If this line was intended to make namespace {target} visible, restart it as '
                            f'`with {target};` before classifiers and regenerate the whole project. '
                            'Do not rename a genuine identifier or replace a valid renames declaration.'})
            offset += len(code_line)
    return advice


# Both groups receive the same production shapes. Metavariables avoid supplying
# concrete library/classifier names or a fixed architecture to copy. Showing the
# full header/body/end shape makes semicolon and section boundaries explicit.
_SOURCE_GUIDANCE = (
    'The following are separate core-AADL production shapes, not a project to copy. '
    '<...> denotes a placeholder; [...] denotes an optional block. Replace placeholders with required names or '
    'declarations and omit unused blocks; never emit the angle brackets, square brackets or explanatory labels.\n'
    'AADL category keywords are literal header words. For a device type use `device N`; for a system type use '
    '`system N`. Matching implementation headers are `device implementation N.i` and `system implementation N.i`. '
    'N and i stand for the required type name and implementation variant. '
    'Never use `component type N` or `implementation device N.i` as a header.\n'
    'Package:\npackage <package_name>\npublic\n[with <other_package>;]\n<declarations>\nend <package_name>;\n'
    'Type form:\n<category> <type_name>\n[features\n  <feature_declarations>]\n'
    '[properties\n  <property_associations>]\nend <type_name>;\n'
    'Implementation form:\n<category> implementation <type_name>.<variant>\n'
    '[subcomponents\n  <child_name>: <child_category> <child_classifier>;]\n'
    '[connections\n  <link_name>: port <source_endpoint> -> <destination_endpoint>;]\n'
    'end <type_name>.<variant>;\n'
    'Data type:\ndata <data_name>\n[properties\n  Data_Size => <byte_count> Bytes;]\nend <data_name>;\n'
    'Headers package, component type and implementation have no trailing semicolon. '
    'Use the matching defining name after end, followed by a semicolon; do not write end package or end features. '
    'A section contains one or more declarations and has no separate end clause. '
    'Omit an unused section rather than emitting an empty features, subcomponents or connections section. '
    'Choose category from the requirements: a device remains device, a system remains system; '
    'its implementation uses the same category as its type. An implementation automatically inherits its type\'s '
    'interfaces: no implements or inherits clause is needed. Use extends only for explicitly intended inheritance '
    'from another classifier. All classifier declarations are peers in their package, not nested inside a type. '
    'Closing a type with end <type_name>; leaves its package open for subsequent declarations. '
    'Finish all declarations belonging to a package block before end <package_name>;. '
    'An implementation must be inside a package body; do not move it after the package closing clause. '
    'Event-port form: <port_name>: <direction> event port; '
    'Data-port form: <port_name>: <direction> data port <data_classifier>; '
    'Event-data-port form: <port_name>: <direction> event data port <data_classifier>; '
    'direction is in, out or in out. Preserve the required event/data/event-data kind and classifier: '
    'an event data port carries data and must not be replaced by a plain event port. '
    'An event port has no data classifier; a data classifier follows port directly, without a type keyword. '
    'A storage size is a Data_Size property association, not a feature named size. '
    'For a type in the same package use its local name. Add with only when referring to a package actually '
    'provided by the explicitly supplied environment or standard libraries, and use its qualified classifier name. '
    'Do not import the containing package or invent a dependency to imitate these syntax shapes. '
    'A standard document title such as AS5506D is not itself an importable AADL package. '
    'Generate exactly one AADL source file in exactly one FILE block. Under the selected OSATE file profile, '
    'that file contains one package or one property set. For a component-modeling requirement, keep all types, '
    'implementations and supporting declarations inside the same package. A classifier referenced earlier '
    'may be defined later within this package, including another system at the same nesting level. '
    'Define every required local target before closing this single file. '
    'Within an implementation, an endpoint on the enclosing component is its local port name; '
    'an endpoint on a child starts with the child instance name, as <child_name>.<port_name>. '
    'Do not prefix an enclosing port with its classifier or implementation name. Use one hyphen in ->. '
    'Create the required declarations and necessary supporting definitions; do not add illustrative data types, '
    'ports or child instances that the architecture does not need. ')

METADATA = {
    'profile': 'AS5506D-core', 'runtime_version': '4', 'standard': STANDARD,
    'generation_profile': 'single_source_file',
    # The active service derives symbols from generated source. Declaration
    # plans are not an input to generation; legacy helpers remain replay-only.
    'generation_interface': 'single_source_no_declaration_plan',
    'generation_file_count': 1,
    'file_compatibility_profile': 'OSATE: one generated source file containing one package or property set; same-file forward references remain deferred',
    'osate_revision': OSATE_REVISION, 'osate_source': OSATE_SOURCE,
    'implemented_scope': (
        'One generated source file per requirement, core declarations, names and visibility within that file '
        'and explicitly supplied libraries, category containment, basic inheritance/refinement, '
        'ordinary port connections and forward interface obligations, scalar/list/record/range property typing, '
        'property units/applicability, property-set imports and qualified classifier owners, constant and list typing, '
        'split public/private packages and implementation visibility views, alias target/category obligations, '
        'inherited mode-transition namespaces and membership domains, data-access/feature-group flow endpoints, '
        'array positivity, and explicit deferred checks. This inventory is not a claim of complete AS5506D coverage.'),
    'unsupported_checks': (
        'Computed properties and effective access rights whose values require unresolved modal/binding context '
        'remain explicit Pending obligations. Instance binding-dependent communication protocol support and '
        'full runtime behavior are not established by declaration checking. Prototype Signature_Match and '
        'modal/computed/context-dependent substitution policies remain explicit Pending obligations. '
        'Annex internals and cross-file generation are outside the selected generation and acceptance scope.'),
    'prototype_policy_scope': (
        'Prototype_Substitution_Rule is read from the formal inline association, determinable inherited classifier '
        'associations, or the loaded Modeling_Properties definition default (currently Type_Extension). '
        'Classifier_Match compares component types; Type_Extension follows component-type ancestry. '
        'Open policy scopes defer checking; Signature_Match is not approximated by subtype checks.'),
    'instance_scope': 'AS5506D 13.1 C1 classifier/process-thread checks and C9 required category presence; local instance reference integrity.',
    'instance_unsupported_checks': 'Complete binding/resource/timing analysis, source-code compliance, behavioral reachability and schedulability are not proved by this hook.',
    'system_prompt': (
        'You are one AADL modeling agent. Implement the supplied requirements using AS5506D core AADL. '
        'Return exactly one complete AADL source file in the supplied container, with exactly one FILE block '
        'and no JSON, Markdown or explanation. '
        'Preserve required classifier names, component categories and interfaces. '
        'The generated file contains one package or one property set; imports refer only to explicitly supplied '
        'or standard library units. Forward classifier references inside the generated package are permitted '
        'and must be defined before file completion. '
        'Ordinary child connections connect an out port to an in port; boundary input ports act as internal sources '
        'and boundary output ports as internal destinations. Keep categories, classifiers and port kinds compatible. '
        'Do not replace requested structure with comments. Annex internal languages are outside this core-language task.'),
    'generation_prompt': ('Generate the complete single-file AADL model for the original requirements. ' + _SOURCE_GUIDANCE),
    'repair_prompt': ('Repair the previous rejected single-file model using the selected repair focus and validator evidence. '
        'The focus is a bounded next correction to inspect, not proof of the only root cause. '
        'Change the erroneous source construct and preserve valid names, interfaces and connections. '
        'Parser-recovery messages after an earlier bad clause may be secondary: do not rename correctly matched '
        'packages or classifiers just to satisfy a downstream recovery message. '
        # Shared syntax guidance is supplied once in the system message by the
        # pipeline. A repair turn adds the actual failure, not another tutorial.
        'Regenerate the entire corrected model as exactly one AADL file in the next response. '
        'Do not return an edit description, a patch or only the changed line.'),
}


class _Check:
    """One immutable-project attribute evaluation; all derived maps are local."""
    def __init__(self, project, plan, final, attributes):
        self.attributes = attributes
        self.project, self.final, self.plan = project, final, _validate_plan(plan)
        self.results, self.packages, self.symbols = [], {}, {}
        self.property_sets, self.property_defs, self.property_types, self.constants = {}, {}, {}, {}
        self.demands, self.interfaces, self.edges = {}, {}, []
        self._feature_cache, self._child_cache = {}, {}
        self._constant_stack = set()
        self._scope_sections = {}
        self._private_completions = {}
        self._prototype_resolution = set()

    def bound(self, node, field):
        """Read a registered synthesized field, with dependencies tracked."""
        fields = self.attributes.get(node, 'fields')
        if field not in fields:
            raise RuleDefinitionError(f'{node.production} has no semantic field binding {field}')
        return next(iter(fields[field]), None)

    def result(self, status, rule, message, node=None, clause=''):
        self.results.append(RuleResult(status=status, rule_id=rule, message=message,
            source=getattr(node, 'source', ''), start=getattr(node, 'start', 0),
            end=getattr(node, 'end', 0), standard=f'{STANDARD} {clause}'.rstrip(), osate=OSATE_SOURCE))

    def conflict(self, rule, message, node=None, clause=''):
        self.result(Status.CONFLICT, rule, message, node, clause)

    def pending(self, rule, message, node=None, clause=''):
        self.result(Status.CONFLICT if self.final else Status.PENDING, rule, message, node, clause)

    def require(self, predicate, rule, message, node=None, clause=''):
        if not predicate:
            self.conflict(rule, message, node, clause)

    def match_end(self, node, name, end_production):
        # Keep this compatibility call site for declaration collection, while
        # the actual equation is now the same registered constraint evaluated
        # by the runtime. There is no second procedural end-name algorithm.
        self.results.extend(result for result in self.attributes.get(node, 'end_constraint')
                            if result.status != Status.SATISFIED)

    def collect(self):
        """Synthesize the entire project environment before resolving references."""
        units = getattr(self.project, 'all_units', None)
        if units is None:
            units = tuple(getattr(self.project, 'dependencies', ())) + tuple(self.project.units)
        for unit in units:
            for node in _walk(unit):
                if node.production == 'property_set':
                    self.collect_property_set(node)
                elif node.production == 'package_spec':
                    self.collect_package(node)
        for private in self._private_completions.values():
            public = private.public_part
            # §4.2 L3/(9)/(10): two source declarations form one implementation,
            # but their visibility views remain separate immutable syntax nodes.
            # External lookup selects the public view; private lookup sees its
            # structural completion and inherits the public header/modes.
            body = _first(public.node, 'implementation_body')
            for section in getattr(body, 'children', ()):
                if not section.production.isupper() and section.production not in {'gap', 'sep', 'modes', 'properties'}:
                    self.conflict('package.public_implementation_shell',
                                  'A split public implementation may contain only properties and modes', section, '§4.2 L3')
            if private.extension or _direct(private.node, 'prototype_bindings'):
                self.conflict('package.private_implementation_header',
                              'The public implementation supplies extends and prototype bindings', private.node, '§4.2(10)')
            private.extension = public.extension

    def collect_package(self, node):
        name_node = self.bound(node, 'name')
        if not _complete(name_node):
            return
        name = _name(name_node)
        if name in self.property_sets:
            self.conflict('name.unique_model_unit', f'Duplicate package/property-set name {name}', name_node, '§4.2')
        package = self.packages.get(name) or _Package(name, node)
        self.packages[name] = package
        package.parts.append(node)
        # §4.2 N2/L2: one public and one private section may be declared in
        # separate package declarations/files, in either physical file order.
        for section in node.children:
            if section.production in {'public_section', 'private_section'}:
                if section.production in package.sections:
                    self.conflict('name.unique_package_section', f'Duplicate {section.production} of package {name}', section, '§4.2 L2')
                else:
                    package.sections[section.production] = section
        self.match_end(node, name, 'end_package_name')
        for imp in _find(node, 'import_declaration'):
            package.imports.update(_name(n) for n in _find(imp, 'import_name') if _complete(n))
        # Rename syntax is interpreted from its production, not guessed from
        # arbitrary identifiers elsewhere in the document.
        for rename in (n for n in _walk(node) if n.production in {'package_alias', 'classifier_alias', 'wildcard_alias'}):
            target = self.bound(rename, 'target')
            if not _complete(target):
                continue
            if rename.production == 'wildcard_alias':
                package.import_all.add(_name(target))
            else:
                alias = self.bound(rename, 'name')
                alias_name = _name(alias) if alias is not None else _name(target).rsplit('::', 1)[-1]
                if alias_name in package.renames:
                    self.conflict('name.unique_alias', f'Duplicate alias {alias_name}', rename, '§4.2')
                package.renames[alias_name] = _name(target)
        for section in getattr(node, 'children', ()):
            if section.production not in {'public_section', 'private_section'}:
                continue
            for child in _walk(section):
                self._scope_sections[id(child)] = section
                if child.production in {'component_type', 'component_implementation', 'feature_group_type'}:
                    self.collect_declaration(package, child, section.production == 'private_section')

    def collect_declaration(self, package, node, private):
        implementation = node.production == 'component_implementation'
        nn = self.bound(node, 'name')
        if not _complete(nn):
            if nn is not None and self.plan is not None:
                prefix = _name(nn)
                candidates = [n.rsplit('::', 1)[-1] for n, item in self.plan.items()
                              if (item['kind'] == 'implementation') == implementation]
                self.require(any(n.startswith(prefix) for n in candidates), 'plan.header_prefix',
                             f'Declaration prefix {prefix!r} is outside the fixed plan', nn)
            return
        name = self.attributes.get(node, 'name')
        category_node = None if node.production == 'feature_group_type' else self.bound(node, 'category')
        if node.production != 'feature_group_type' and not _complete(category_node):
            return
        category = self.attributes.get(node, 'category')
        if not category:
            return
        key = package.name + '::' + name
        previous = self.symbols.get(key)
        if previous is not None and not (implementation and previous.implementation and previous.private != private
                                         and key not in self._private_completions):
            self.conflict('name.unique_classifier', f'Duplicate classifier {key}', nn, '§4.2')
            return
        ext = self.bound(node, 'extension')
        declaration = _Declaration(key, name, package, category, implementation, node, private,
                                   self.attributes.get(node, 'extension'))
        if previous is not None:
            public, completion = (previous, declaration) if private else (declaration, previous)
            self.require(public.category == completion.category, 'package.implementation_category',
                         'Public and private implementation declarations must have the same category', node, '§4.2 L3/§4.4 L3')
            completion.public_part = public
            self.symbols[key] = public
            self._private_completions[key] = completion
        else:
            self.symbols[key] = declaration
        self.match_end(node, name, 'end_implementation_name' if implementation else 'end_name')
        for feature in _section_items(node, 'features', 'feature'):
            value = self.feature(feature)
            if value:
                self.put_member(declaration.features, value.name, value, declaration, feature)
        for child in _section_items(node, 'subcomponents', 'subcomponent'):
            name_node, category_node = self.bound(child, 'name'), self.bound(child, 'category')
            if not _complete(name_node):
                continue
            if not _complete(category_node):
                # The name is fixed before the category is: after ``x:`` a
                # second local x cannot be repaired by finishing ``system``.
                # Do not invent a typed child or consult inherited members here;
                # a refinement can legitimately reuse an inherited name. At an
                # open identifier the frontend also retains an incomplete-name
                # alternative, which the runtime must still consider viable.
                if _name(name_node) in declaration.children:
                    self.conflict('name.unique_member',
                                  f'Duplicate member {declaration.key}.{_name(name_node)}', child, '§4.3')
                continue
            reference_node = self.bound(child, 'classifier')
            value = _Child(_name(self.bound(child, 'name')), _text(self.bound(child, 'category')),
                           _name(reference_node) if _complete(reference_node) else '', child, _first(child, 'refinement') is not None)
            self.put_member(declaration.children, value.name, value, declaration, child)
        for section, production, target in (
                ('connections', 'connection', declaration.connections),
                ('modes', 'mode', declaration.modes), ('type_modes', 'mode', declaration.modes),
                ('modes', 'mode_transition', declaration.transitions),
                ('type_modes', 'mode_transition', declaration.transitions),
                ('flow_specs', 'flow_spec', declaration.flows),
                ('flow_implementations', 'end_to_end_flow', declaration.end_to_end),
                ('prototypes', 'prototype', declaration.prototypes),
                ('calls', 'subprogram_call', declaration.calls)):
            for member in _section_items(node, section, production):
                name_node = _first(member, 'declaration_name', 'flow_reference')
                if _complete(name_node):
                    self.put_member(target, _name(name_node), member, declaration, member)
        for section, production, target in (('internal_features', 'internal_feature', declaration.internal),
                                             ('processor_features', 'processor_feature', declaration.processor)):
            for member in _section_items(node, section, production):
                name_node = self.bound(member, 'name')
                if not _complete(name_node):
                    continue
                words = _tokens(member.text)
                reference = self.bound(member, 'classifier')
                if production == 'internal_feature':
                    kind = 'event data port' if 'data' in words else 'event port'
                else:
                    kind = 'subprogram access' if 'subprogram' in words else 'port'
                value = _Feature(_name(name_node), kind, 'out', _name(reference) if _complete(reference) else '', member)
                self.put_member(target, value.name, value, declaration, member)

    def put_member(self, target, name, value, owner, node):
        if name in target:
            self.conflict('name.unique_member', f'Duplicate member {owner.key}.{name}', node, '§4.3')
        target[name] = value

    def feature(self, node):
        nn = self.bound(node, 'name')
        if not _complete(nn):
            return None
        spec = self.bound(node, 'spec')
        if spec is None:
            return None
        typed = next((n for n in _walk(spec) if n.production in {
            'port_spec', 'access_spec', 'parameter_spec', 'feature_group_spec', 'abstract_feature_spec'}), None)
        if typed is None:
            return None
        words = _tokens(typed.text)
        direction = _text(_first(typed, 'direction', recursive=True))
        if not direction:
            direction = 'in out' if words[:2] == ['in', 'out'] else (words[0] if words and words[0] in {'in', 'out', 'requires', 'provides'} else '')
        if typed.production == 'port_spec':
            if 'port' not in words:
                return None
            before = words[:words.index('port')]
            kind = 'event data port' if 'event' in before and 'data' in before else ('event port' if 'event' in before else 'data port')
        elif typed.production == 'access_spec':
            if 'access' not in words:
                return None
            kind = ' '.join(w for w in words[:words.index('access')] if w not in {'requires', 'provides'}) + ' access'
        else:
            kind = {'parameter_spec': 'parameter', 'feature_group_spec': 'feature group', 'abstract_feature_spec': 'feature'}[typed.production]
        reference_node = _first(typed, 'classifier_reference', recursive=True)
        return _Feature(_name(nn), kind, direction, _name(reference_node) if _complete(reference_node) else '', node,
                        _first(node, 'refinement') is not None, 'inverse' in words)

    def qualified(self, package, reference, node=None):
        # This inherited scope is produced by registered attributes. The local
        # checker retains mutable obligation maps only; name visibility is a
        # read-only value owned by the current syntax snapshot.
        section = self._scope_sections.get(id(node), package.node)
        scope = self.attributes.get(section, 'scope')
        if scope['package'] != package.name:
            # Some inherited-member checks pass a diagnostic node from another
            # owner. Do not borrow that unrelated package's import namespace.
            scope = self.attributes.get(package.node, 'scope')
        renames, imports, import_all = scope['renames'], scope['imports'], scope['import_all']
        reference = reference.lower()
        if '::' not in reference:
            if reference in renames:
                reference = renames[reference]
            elif '.' in reference and reference.split('.', 1)[0] in renames:
                base, suffix = reference.split('.', 1)
                reference = renames[base] + '.' + suffix
            else:
                local = package.name + '::' + reference
                if local in self.symbols or not import_all:
                    return local
                matches = [p + '::' + reference for p in import_all if p + '::' + reference in self.symbols]
                if len(matches) > 1:
                    self.conflict('name.unambiguous', f'Ambiguous classifier {reference}', node, '§4.2')
                return matches[0] if len(matches) == 1 else local
        target_package, name = reference.rsplit('::', 1)
        target_package = renames.get(target_package, target_package)
        if target_package != package.name and target_package not in imports and target_package not in import_all:
            # §4.2 N5 limits public uses to public imports; private uses inherit
            # those imports and may add private ones. A classifier declaration
            # has already passed its section's visibility_declaration* prefix,
            # so appending text cannot repair a missing import at this use.
            report = self.pending if (scope.get('section') == 'private' and
                                     self.public_visibility_open(package)) else self.conflict
            report('name.package_visibility',
                          f'{target_package} is not imported in the {scope.get("section", "package")} scope of {package.name}',
                          node, '§4.2 N5')
        return target_package + '::' + name

    def public_visibility_open(self, package):
        """Can a later public part still supply imports to this private part?"""
        if self.final:
            return False
        public = package.sections.get('public_section')
        if public is None:
            return True
        # Once a declaration starts, visibility_declaration* has finished.
        return not _complete(public) and not _find(public, 'aadl_declaration')

    def resolve(self, owner, reference, categories=None, node=None, implementation=None):
        if not reference:
            return None
        if '::' not in reference and '.' not in reference:
            scopes = self.namespace_ancestors(owner)
            formal = next((scope.prototypes[reference] for scope in scopes if reference in scope.prototypes), None)
            if formal is not None:
                spec = _first(formal, 'component_prototype', 'feature_group_prototype', 'feature_prototype', recursive=True)
                category_node = _first(spec, 'category')
                category = _text(category_node) if category_node is not None else (
                    'feature group' if getattr(spec, 'production', '') == 'feature_group_prototype' else '')
                if categories and category and category != 'abstract':
                    self.require(category in categories, 'prototype.reference_category',
                                 f'Prototype {reference} is {category}, not {sorted(categories)}', node, '§4.7')
                # Bindings in the public header also apply to its private
                # completion. Inspect only classifier headers, never bindings
                # belonging to a nested subcomponent using another classifier.
                for scope in scopes:
                    headers = (scope.node,) + _direct(scope.node, 'type_extension')
                    for header in headers:
                        for bindings in _direct(header, 'prototype_bindings'):
                            for binding in _direct(bindings, 'prototype_binding'):
                                if _field(binding, 'prototype_name') != reference:
                                    continue
                                actual = _first(binding, 'prototype_actual')
                                values = _find(actual, 'component_actual')
                                if len(values) != 1 or _first(actual, 'component_actual_list') is not None:
                                    return None  # array actuals are checked by check_prototype_actual
                                target = _first(values[0], 'classifier_reference')
                                if not _complete(target):
                                    self.pending('prototype.binding_complete', f'Complete actual for {reference}', binding, '§4.7')
                                    return None
                                identity = (id(owner.node), reference)
                                if identity in self._prototype_resolution:
                                    self.conflict('prototype.binding_cycle', f'Cyclic prototype binding for {reference}', binding, '§4.7')
                                    return None
                                self._prototype_resolution.add(identity)
                                try:
                                    return self.resolve(scope, _name(target), categories, target, implementation)
                                finally:
                                    self._prototype_resolution.remove(identity)
                # Unbound generic prototypes are legal declarations. They are
                # not fabricated package classifiers or unresolved package names.
                return None
            if self.namespace_can_grow(owner, include_local=False):
                # An inherited formal may shadow a same-spelled package
                # classifier, including one with a different category. Until
                # every ancestor's prototype namespace is known, do not turn
                # this use into a package classifier or contaminate its category
                # obligations. Closing the current package does not close a
                # forward-defined ancestor in another package (§4.7).
                self.pending('prototype.inherited_scope',
                             f'Resolve inherited prototype scope for {owner.key}.{reference}', node, '§4.7')
                return None
        key = self.qualified(owner.package, reference, node if node is not None else owner.node)
        requested = set(categories or CATEGORIES + ('feature group',))
        prior = self.demands.setdefault(key, requested.copy())
        prior &= requested
        if not prior:
            self.conflict('reference.category_obligations', f'Incompatible category obligations for {key}', node, '§4.3')
        # Resolve the actual syntax reference through the dependency-aware
        # namespace lookup. Synthetic lookups (e.g. the type of A.impl) use the
        # same synthesized namespace and then retrieve the local checker record.
        reference_node = next((n for n in _find(node, 'classifier_reference')
                               if _complete(n) and _name(n) == reference), None) if node else None
        if reference_node is not None:
            signature = self.attributes.get(reference_node, 'target')
        else:
            signature = self.attributes.get(self.attributes.root, 'symbols').get(key)
        found = self.symbols.get(key) if signature is not None and not isinstance(signature, Missing) else None
        if found is not None and owner.private and found.package.name == owner.package.name:
            found = self._private_completions.get(key, found)
        if found is None:
            # Prototypes are declarations with deferred classifier binding, not
            # global fake classifiers introduced merely to satisfy a reference.
            scope_section = self._scope_sections.get(id(node), None)
            containing_part = next((part for part in owner.package.parts
                                    if scope_section is not None and scope_section in part.children), owner.package.node)
            closing = any(c.production == 'end_package_name' or
                          c.production.isupper() and _name(c) in {'e', 'en', 'end'}
                          for c in getattr(containing_part, 'children', ()))
            # A private-only part may precede its public declaration in another
            # file. A closed private file cannot close that missing namespace.
            if owner.private and not self.final:
                public = owner.package.sections.get('public_section')
                if public is None or not _complete(public):
                    closing = False
            if key.rsplit('::', 1)[0] == owner.package.name and closing:
                self.conflict('reference.package_closed', f'Package closure prevents defining {key}', node, '§4.2/§4.3')
            else:
                self.pending('reference.defined', f'Define classifier {key}', node, '§4.2/§4.3')
            return None
        if found.private and (found.package.name != owner.package.name or not owner.private):
            # Only an implementation may gain a matching public declaration
            # later (§4.2 L3). A private type can never be exported by appending
            # a public duplicate, so its visibility conflict is immediate.
            public = found.package.sections.get('public_section')
            can_be_exported = found.implementation and not self.final and (public is None or not _complete(public))
            report = self.pending if can_be_exported else self.conflict
            report('reference.private_visibility', f'Private classifier {key} needs a visible public declaration', node, '§4.2 N9/L3')
        if found.category not in requested:
            self.conflict('reference.category', f'{key} is {found.category}; expected {sorted(requested)}', node, '§4.3')
        if implementation is not None and found.implementation != implementation:
            self.conflict('reference.classifier_kind', f'Wrong type/implementation kind for {key}', node, '§4.3')
        return found

    def inherited(self, owner, attribute, active=()):
        cache = self._feature_cache if attribute == 'features' else self._child_cache
        identity = (id(owner.node), attribute)
        if identity in cache:
            return cache[identity]
        if owner.key in active:
            self.conflict('inheritance.acyclic', f'Classifier extension cycle through {owner.key}', owner.node, '§4.3')
            return {}
        result = {}
        if owner.public_part is not None:
            result.update(self.inherited(owner.public_part, attribute, active))
        if owner.implementation and attribute in {'features', 'prototypes', 'modes', 'transitions', 'flows'}:
            base = self.resolve(owner, owner.name.split('.', 1)[0], {owner.category}, owner.node, False)
            if base:
                result.update(self.inherited(base, attribute, active + (owner.key,)))
        if owner.extension:
            base = self.resolve(owner, owner.extension, {owner.category, 'abstract'}, owner.node, owner.implementation)
            if base:
                result.update(self.inherited(base, attribute, active + (owner.key,)))
        for name, member in getattr(owner, attribute).items():
            old = result.get(name)
            refined = getattr(member, 'refined', False) if isinstance(member, (_Feature, _Child)) else _first(member, 'refinement') is not None
            if old is not None and not refined:
                self.conflict('inheritance.refinement_required', f'{owner.key}.{name} redeclares an inherited member without refined to', getattr(member, 'node', member), '§4.3')
            if old is None and refined:
                # A future extension definition can still provide the member.
                if owner.extension and self.qualified(owner.package, owner.extension, owner.node) not in self.symbols:
                    self.pending('inheritance.refinement_target', f'Resolve inherited member {owner.key}.{name}', getattr(member, 'node', member), '§4.3')
                else:
                    self.conflict('inheritance.refinement_target', f'No inherited member {name} to refine', getattr(member, 'node', member), '§4.3')
            if old is not None and refined:
                if isinstance(member, _Feature):
                    self.require(member.kind == old.kind or old.kind == 'feature', 'inheritance.feature_kind',
                                 f'Refinement changes kind of {name}', member.node, '§4.3')
                    self.require(not old.direction or member.direction == old.direction, 'inheritance.direction',
                                 f'Refinement changes direction of {name}', member.node, '§4.3')
                elif isinstance(member, _Child):
                    self.require(member.category == old.category or old.category == 'abstract', 'inheritance.category',
                                 f'Refinement changes category of {name}', member.node, '§4.3')
            result[name] = member
        cache[identity] = result
        return result

    def collect_property_set(self, node):
        nn = self.bound(node, 'name')
        if not _complete(nn):
            return
        name = _name(nn)
        if name in self.property_sets or name in self.packages:
            self.conflict('name.unique_model_unit', f'Duplicate property set/package {name}', nn, '§11')
        self.property_sets[name] = node
        self.match_end(node, name, 'end_name')
        for declaration in _walk(node):
            kind = declaration.production
            if kind not in {'property_type_declaration', 'property_definition', 'property_constant'}:
                continue
            dn = self.bound(declaration, 'name')
            if not _complete(dn):
                continue
            key = name + '::' + _name(dn)
            if key in self.property_defs or key in self.property_types or key in self.constants:
                self.conflict('property.unique_declaration', f'Duplicate property declaration {key}', dn, '§11')
            if not _complete(declaration):
                continue
            words = _tokens(declaration.text)
            if ':' not in words:
                continue
            body = words[words.index(':') + 1:]
            if body and body[-1] == ';':
                body = body[:-1]
            if kind == 'property_type_declaration':
                self.property_types[key] = self.type_descriptor(body[1:] if body[:1] == ['type'] else body, name, declaration)
            elif kind == 'property_constant':
                split = _top_index(body, '=>')
                if split is not None:
                    self.constants[key] = {'type': self.type_descriptor(body[1:split], name, declaration),
                                           'value': body[split + 1:], 'node': declaration, 'scope': name}
            else:
                split = _top_index(body, 'applies')
                if split is None:
                    continue
                value_split = _top_index(body[:split], '=>')
                typ = body[:value_split if value_split is not None else split]
                typ = [w for w in typ if w != 'inherit']
                owners = _split_top(body[split + 3:-1], ',')
                self.property_defs[key] = {'type': self.type_descriptor(typ, name, declaration),
                    'owners': tuple(''.join(w) if '::' in w else ' '.join(w) for w in owners), 'node': declaration,
                    'scope': name, 'default': body[value_split + 1:split] if value_split is not None else None}

    def type_descriptor(self, words, scope, node):
        """Synthesize property types from their own declarations, including units."""
        if not words:
            return {'kind': 'pending', 'scope': scope, 'node': node}
        common = {'scope': scope, 'node': node}
        head = words[0]
        if head == 'list' and words[1:2] == ['of']:
            return dict(common, kind='list', item=self.type_descriptor(words[2:], scope, node))
        if head == 'range' and words[1:2] == ['of']:
            return dict(common, kind='range', item=self.type_descriptor(words[2:], scope, node))
        if head in {'aadlboolean', 'aadlstring'}:
            return dict(common, kind='boolean' if head == 'aadlboolean' else 'string')
        if head == 'enumeration':
            values = [''.join(w) for w in _split_top(words[2:-1], ',')]
            self.require(len(values) == len(set(values)), 'property.unique_literal',
                         'Enumeration literals must be distinct', node, '§11')
            return dict(common, kind='enumeration', values=set(values))
        if head == 'units':
            units, ordered = {}, []
            for part in _split_top(words[2:-1], ','):
                if not part:
                    continue
                name = part[0]
                if name in units:
                    self.conflict('property.unique_unit', f'Duplicate unit {name}', node, '§11')
                if len(part) == 1:
                    units[name] = Decimal(1)
                elif len(part) >= 5 and part[1] == '=>' and part[3] == '*':
                    base = units.get(part[2])
                    if base is None:
                        self.conflict('property.unit_order', f'Unit {name} references a unit not declared earlier: {part[2]}', node, '§11')
                    try:
                        factor = _number(''.join(part[4:]))
                        self.require(factor > 0, 'property.unit_factor', 'Unit conversion must be positive', node, '§11')
                        units[name] = (base or Decimal(1)) * factor
                    except (ValueError, InvalidOperation):
                        self.conflict('property.unit_factor', f'Invalid unit conversion for {name}', node, '§11')
                ordered.append(name)
            return dict(common, kind='units', units=units)
        if head in {'aadlinteger', 'aadlreal'}:
            unit_index = _top_index(words, 'units')
            unit_words = words[unit_index + 1:] if unit_index is not None else []
            if unit_words[:1] == ['(']:
                unit_words = ['units'] + unit_words
            unit_type = self.type_descriptor(unit_words, scope, node) if unit_index is not None else None
            bounds = words[1:unit_index] if unit_index is not None else words[1:]
            return dict(common, kind='integer' if head == 'aadlinteger' else 'real', units=unit_type,
                        bounds=_split_top(bounds, '..') if bounds else ())
        if head in {'classifier', 'reference'}:
            allowed = tuple(' '.join(part) for part in _split_top(words[2:-1], ',')) if len(words) > 1 else ()
            return dict(common, kind=head, owners=allowed)
        if head == 'record':
            fields = {}
            for part in _split_top(words[2:-1], ';'):
                if ':' in part:
                    i = part.index(':')
                    name = ''.join(part[:i])
                    if name in fields:
                        self.conflict('property.unique_record_field', f'Duplicate record field {name}', node, '§11')
                    fields[name] = self.type_descriptor(part[i + 1:], scope, node)
            return dict(common, kind='record', fields=fields)
        return dict(common, kind='named', reference=''.join(words))

    def property_key(self, reference, scope, mapping):
        if '::' in reference:
            return reference
        # §11.1 N4 and §11.1.3 N2 require qualification even inside the
        # declaring property set. Bare names select predeclared entities only;
        # falling back to scope::name would silently accept a user constant.
        key = 'aadl_project::' + reference
        if key in mapping:
            return key
        # Predeclared property names are implicitly visible; user property sets
        # do not gain global visibility merely by sharing an unqualified name.
        predefined = {name[:-5].lower() for name in BUILTIN_SOURCES}
        matches = [key for key in mapping if key.endswith('::' + reference) and key.rsplit('::', 1)[0] in predefined]
        return matches[0] if len(matches) == 1 else reference

    def resolve_type(self, desc, active=()):
        if desc is None:
            return None
        if desc['kind'] != 'named':
            return desc
        self.check_property_reference_visibility(desc['reference'], desc['scope'], desc['node'])
        key = self.property_type_key(desc)
        if key is None:
            return None
        if key in active:
            self.conflict('property.type_acyclic', f'Property type cycle through {key}', desc['node'], '§11')
            return None
        target = self.property_types.get(key)
        if target is None:
            self.pending('property.type_defined', f'Define property type {key}', desc['node'], '§11')
            return None
        return self.resolve_type(target, active + (key,))

    def property_type_key(self, desc):
        # User-defined type names, like constants, require their property-set
        # qualifier even inside that same set (§11.1 N4 / §11.1.1 N2).
        # Bare type names can only select a predeclared type such as Time.
        reference = desc['reference']
        key = self.property_key(reference, '', self.property_types)
        if '::' not in reference and key not in self.property_types:
            self.conflict('property.type_qualification', f'User-defined property type {reference} requires its property-set qualifier', desc['node'], '§11.1.1 N2')
            return None
        return key

    def check_property_reference_visibility(self, reference, scope, node):
        """Qualified names still require a visible model-unit import.

        Both property sets and package sections have fixed import prefixes;
        defining the referenced set in a later file cannot repair an omitted
        import at a use already generated (§4.2 N5, §11.1).
        """
        if '::' not in reference:
            return
        target = reference.rsplit('::', 1)[0]
        if target == scope or target in {name[:-5].lower() for name in BUILTIN_SOURCES}:
            return
        if scope in self.property_sets:
            imports = {_name(n) for declaration in _find(self.property_sets[scope], 'import_declaration')
                       for n in _find(declaration, 'import_name') if _complete(n)}
        elif scope in self.packages:
            section = self._scope_sections.get(id(node), self.packages[scope].node)
            imports = self.attributes.get(section, 'scope')['imports']
        else:
            return
        self.require(target in imports, 'property.reference_visibility',
                     f'Model unit {target} is not imported in {scope}', node, '§4.2 N5 / §11.1')

    def numeric_value(self, words, desc, active=(), value_scope=None):
        if not words:
            return None
        sign = words[0] if words[0] in {'+', '-'} else ''
        scalar = words[1] if sign and len(words) > 1 else words[0]
        after = words[2:] if sign else words[1:]
        try:
            value = _number(sign + scalar)
        except (ValueError, InvalidOperation):
            reference = ''.join(words[1:] if sign else words)
            key = self.property_key(reference, value_scope or desc['scope'], self.constants)
            if key in active:
                self.conflict('property.constant_acyclic', f'Property constant cycle through {key}', desc['node'], '§11')
                return None
            constant = self.constants.get(key)
            if constant is not None:
                resolved = self.numeric_value(constant['value'], desc, active + (key,), constant['scope'])
                return -resolved if resolved is not None and sign == '-' else resolved
            return None
        units = self.resolve_type(desc.get('units'))
        if units:
            if units['kind'] != 'units' or len(after) != 1 or after[0] not in units['units']:
                return None
            return value * units['units'][after[0]]
        return value if not after else None

    def property_types_match(self, actual, expected, active=()):
        """Compare constant types before substituting their literal values.

        A boolean constant cannot become an integer just because a later
        evaluator happens to accept its text. Numeric subranges are checked
        separately against the receiving property's bounds (§11.4 L3).
        """
        actual, expected = self.resolve_type(actual), self.resolve_type(expected)
        if actual is None or expected is None:
            return None
        pair = (id(actual), id(expected))
        if pair in active:
            return None
        if actual['kind'] != expected['kind']:
            return False
        kind = actual['kind']
        if kind in {'list', 'range'}:
            return self.property_types_match(actual['item'], expected['item'], active + (pair,))
        if kind in {'integer', 'real'}:
            left, right = actual.get('units'), expected.get('units')
            if (left is None) != (right is None):
                return False
            return self.property_types_match(left, right, active + (pair,)) if left else True
        if kind in {'enumeration', 'units'}:
            # Literal identities belong to the declaring type namespace.
            # Reusing its named type resolves to this same descriptor; an
            # unrelated declaration with equally spelled literals does not.
            return actual is expected
        if kind == 'record':
            if actual['fields'].keys() != expected['fields'].keys():
                return False
            answers = [self.property_types_match(actual['fields'][name], expected['fields'][name], active + (pair,))
                       for name in actual['fields']]
            return False if False in answers else None if None in answers else True
        return True

    def validate_value(self, words, desc, node, owner=None, complete=True, value_scope=None):
        # A named type may live in a different property set than the value.
        # Keep expression lookup in its lexical scope when resolving that type,
        # and explicitly switch scopes when expanding a constant definition.
        value_scope = value_scope or (owner.package.name if owner is not None else (desc or {}).get('scope', ''))
        desc = self.resolve_type(desc)
        if desc is None or not words:
            return
        kind = desc['kind']
        if complete and words and not words[0].startswith('"'):
            reference = ''.join(words)
            constant_key = self.property_key(reference, value_scope, self.constants)
            constant = self.constants.get(constant_key)
            if constant is not None:
                self.check_property_reference_visibility(reference, value_scope, node)
                matches = self.property_types_match(constant['type'], desc)
                if matches is False:
                    self.conflict('property.constant_type', f'Constant {constant_key} has a different property type', node, '§11.4 L3')
                    return
                if constant_key in self._constant_stack:
                    self.conflict('property.constant_acyclic', f'Property constant cycle through {constant_key}', node, '§11')
                    return
                self._constant_stack.add(constant_key)
                try:
                    self.validate_value(constant['value'], desc, constant['node'], owner, True, constant['scope'])
                finally:
                    self._constant_stack.remove(constant_key)
                return
        # computed values and property references require context-sensitive
        # evaluation. Their names are resolved; they are not converted to an
        # invented scalar simply to make a type check succeed.
        if words[0] == 'compute':
            self.pending('property.computed_value', 'Computed property requires a registered computation/evaluator', node, '§11')
            return
        if kind == 'list':
            if words[0] != '(':
                if complete:
                    self.conflict('property.value_type', 'List property requires a parenthesized list', node, '§11')
                return
            for value in _split_top(words[1:-1] if words[-1:] == [')'] else words[1:], ','):
                if value:
                    self.validate_value(value, desc['item'], node, owner, complete, value_scope)
            return
        if kind == 'range':
            parts = _split_top(words, '..')
            if len(parts) == 2:
                # A delta is a third numeric term, not part of the upper
                # bound's unit spelling (§11.4 S6). In a prefix it may still
                # be empty, so only completed terms are checked strictly.
                upper = _split_top(parts[1], 'delta')
                terms = [parts[0], *upper]
                for part in terms:
                    self.validate_value(part, desc['item'], node, owner, complete, value_scope)
                scalar = self.resolve_type(desc['item'])
                if scalar:
                    a, b = self.numeric_value(parts[0], scalar, value_scope=value_scope), self.numeric_value(upper[0], scalar, value_scope=value_scope)
                    if a is not None and b is not None:
                        self.require(a <= b, 'property.range_order', 'Range lower bound exceeds upper bound', node, '§11')
            elif complete:
                self.conflict('property.value_type', 'Range property requires lower .. upper', node, '§11')
            return
        if kind == 'record':
            if words[0] != '[':
                if complete:
                    self.conflict('property.value_type', 'Record property requires [field => value; ...]', node, '§11')
                return
            seen = set()
            for assignment in _split_top(words[1:-1] if words[-1:] == [']'] else words[1:], ';'):
                at = _top_index(assignment, '=>')
                if at is None:
                    continue
                name = ''.join(assignment[:at])
                if name not in desc['fields'] or name in seen:
                    self.conflict('property.record_field', f'Unknown or duplicate record field {name}', node, '§11')
                else:
                    self.validate_value(assignment[at + 1:], desc['fields'][name], node, owner, complete, value_scope)
                seen.add(name)
            return
        if kind in {'integer', 'real'}:
            units = self.resolve_type(desc.get('units'))
            offset = 1 if words[0] in {'+', '-'} else 0
            if len(words) <= offset:
                return
            try:
                numeric = _number((''.join(words[:offset])) + words[offset])
            except (ValueError, InvalidOperation):
                key = self.property_key(''.join(words[offset:]), value_scope, self.constants)
                if key in self.constants:
                    self.check_property_reference_visibility(''.join(words[offset:]), value_scope, node)
                    if self.property_types_match(self.constants[key]['type'], desc) is False:
                        self.conflict('property.constant_type', f'Constant {key} has a different numeric property type', node, '§11.4 L3')
                        return
                    value = self.numeric_value(words, desc, value_scope=value_scope)
                    if value is not None:
                        if kind == 'integer':
                            self.require(value == value.to_integral_value(), 'property.integer_value',
                                         'Integer property requires an integer constant', node, '§11')
                        bounds = desc.get('bounds', ())
                        if len(bounds) == 2:
                            low, high = self.numeric_value(bounds[0], desc), self.numeric_value(bounds[1], desc)
                            self.require(low is None or value >= low, 'property.lower_bound', 'Constant is below type range', node, '§11')
                            self.require(high is None or value <= high, 'property.upper_bound', 'Constant exceeds type range', node, '§11')
                elif words[offset].startswith('"'):
                    self.conflict('property.value_type', 'Numeric property cannot contain a string', node, '§11')
                elif complete:
                    self.pending('property.value_reference', f'Resolve numeric property value {"".join(words)}', node, '§11')
                return
            rest = words[offset + 1:]
            if kind == 'integer' and ('.' in words[offset] or numeric != numeric.to_integral_value()):
                self.conflict('property.integer_value', 'Integer property requires an integer literal, not a real literal', node, '§11.4 L1')
            if units and units.get('kind') == 'units':
                allowed = set(units['units'])
                if rest:
                    unit = rest[0]
                    exact = complete or bool(node.text and node.text[-1].isspace())
                    if len(rest) > 1 or not (unit in allowed if exact else any(u.startswith(unit) for u in allowed)):
                        self.conflict('property.unit_prefix', f'Unit {unit!r} cannot match an allowed unit', node, '§11')
                elif complete:
                    self.conflict('property.unit_required', 'A physical numeric property requires a unit', node, '§11')
            elif rest and complete:
                self.conflict('property.unexpected_unit', 'Unitless numeric property has an extra unit/value', node, '§11')
            if complete:
                value = self.numeric_value(words, desc, value_scope=value_scope)
                bounds = desc.get('bounds', ())
                if value is not None and len(bounds) == 2:
                    low, high = self.numeric_value(bounds[0], desc), self.numeric_value(bounds[1], desc)
                    if low is not None:
                        self.require(value >= low, 'property.lower_bound', 'Property value is below its type range', node, '§11')
                    if high is not None:
                        self.require(value <= high, 'property.upper_bound', 'Property value exceeds its type range', node, '§11')
            return
        if kind == 'string':
            if complete and (len(words) != 1 or not words[0].startswith('"')):
                self.conflict('property.value_type', 'String property requires an AADL string', node, '§11')
        elif kind == 'boolean':
            if complete and any(w not in {'true', 'false', 'not', 'and', 'or', '(', ')'} for w in words):
                key = self.property_key(''.join(words), value_scope, self.constants)
                if key not in self.constants:
                    self.pending('property.value_reference', f'Resolve boolean property value {"".join(words)}', node, '§11')
        elif kind == 'enumeration':
            value = ''.join(words)
            if complete and value not in desc['values']:
                # An identifier can instead denote a forward-declared
                # constant. Rejecting by literal prefix alone would mask all
                # such references before the defining property set appears.
                self.pending('property.enumeration_value', f'Resolve {value} as an enumeration literal or matching constant', node, '§11.4')
        elif kind == 'units':
            value = ''.join(words)
            if complete and value not in desc['units']:
                self.pending('property.units_value', f'Resolve {value} as a unit literal or matching constant', node, '§11.4')
        elif kind == 'classifier':
            if words[:2] == ['classifier', '('] and words[-1:] == [')'] and owner is not None:
                target = self.resolve(owner, ''.join(words[2:-1]), None, node)
                if target is not None and desc['owners']:
                    self.require(self.property_owner_matches(target, set(desc['owners']), owner, node),
                                 'property.classifier_type', 'Classifier is outside the property type category restriction', node, '§11.1.1 S10')
            elif complete:
                self.conflict('property.value_type', 'Classifier property requires classifier(...)', node, '§11')
        elif kind == 'reference':
            if words[:2] == ['reference', '('] and words[-1:] == [')'] and owner is not None:
                target = self.path(owner, ''.join(words[2:-1]), node)
                if target is not None and desc['owners']:
                    self.require(self.property_owner_matches(target, set(desc['owners']), owner, node),
                                 'property.reference_type', 'Referenced element is outside the property type category restriction', node, '§11.1.1 S11')
            elif complete:
                self.conflict('property.value_type', 'Reference property requires reference(...)', node, '§11')

    def check_property(self, node, owner, inline_target=None):
        pn = self.bound(node, 'name')
        if not _complete(pn):
            return
        name = _name(pn)
        scope = owner.package.name if owner is not None else ''
        key = self.property_key(name, scope, self.property_defs)
        definition = self.property_defs.get(key)
        if definition is None:
            self.pending('property.defined', f'Define property {key}', pn, '§11')
            return
        pset = key.rsplit('::', 1)[0]
        if owner is not None and pset not in {n[:-5].lower() for n in BUILTIN_SOURCES}:
            section = self._scope_sections.get(id(pn), owner.node)
            imports = self.attributes.get(section, 'scope')['imports']
            self.require(pset in imports or pset == owner.package.name,
                         'property.visibility', f'Property set {pset} is not imported', pn, '§11')
        operator = _text(_first(node, 'assignment_operator'))
        if operator == '+=>':
            typ = self.resolve_type(definition['type'])
            if typ:
                self.require(typ['kind'] == 'list', 'property.append_list',
                             'The +=> append operator requires a list property', node, '§11')
            self.require(not _find(node, 'in_modes') and _first(node, 'in_binding') is None,
                         'property.append_context', 'Append associations cannot be modal or binding-specific', node, '§11.3 L6')
            self.require(_first(node, 'applies_to') is None, 'property.append_contained',
                         'Append associations cannot have applies to', node, '§11.3 L7')
        binding = self.bound(node, 'binding')
        if binding is not None and owner is not None:
            seen_bindings = set()
            for reference in _direct(binding, 'classifier_reference'):
                if _complete(reference):
                    value = _name(reference)
                    if value in seen_bindings:
                        self.conflict('property.unique_binding', f'Duplicate binding classifier {value}', reference, '§11.3')
                    seen_bindings.add(value)
                    self.resolve(owner, value, {'processor', 'virtual processor', 'memory', 'bus', 'virtual bus'}, reference, False)
        values = _find(node, 'property_value')
        # Keep outer values only: validating every nested record/list scalar
        # against the outer property's type would create false type errors.
        outer_values = []
        def visit(current, inside=False):
            if current.production == 'property_value' and not inside:
                outer_values.append(current)
                return
            for child in getattr(current, 'children', ()):
                visit(child, inside)
        visit(node)
        for value in outer_values:
            self.validate_value(_tokens(value.text), definition['type'], value, owner, _complete(value))
        applies = _first(node, 'applies_to')
        if applies is not None and _find(node, 'in_modes'):
            # A second completed target already fixes the violation; an
            # unfinished first target does not. This is independent of whether
            # those targets have been declared yet (§11.3 L2).
            paths = _find(applies, 'contained_path')
            self.require(len(paths) <= 1, 'property.modal_single_target',
                         'A modal contained association must have exactly one target path', applies, '§11.3 L2')
        if inline_target is not None and applies is not None:
            self.conflict('property.inline_applies', 'Inline properties cannot have applies to', applies, '§11')
        if owner is None:
            return
        targets = []
        if applies is not None:
            for path in _find(applies, 'contained_path'):
                found = self.path(owner, _name(path), path)
                if found is not None:
                    targets.append(found)
        else:
            targets.append(inline_target if inline_target is not None else owner)
        allowed = set(definition['owners'])
        for target in targets:
            # Abstract components implicitly accept every property, even when
            # the property's applies-to list names only a concrete category.
            if getattr(target, 'category', '') != 'abstract' and not self.property_owner_matches(target, allowed, owner, node):
                self.conflict('property.applies_to', f'{name} does not apply to {sorted(_owner_labels(target))}', node, '§11.1.2 L1 / §11.3 L1')

    def property_owner_matches(self, target, allowed, owner, node):
        """Apply meta-class restrictions and classifier ancestry separately."""
        labels = _owner_labels(target) | {'named element', 'namedelement'}
        category = getattr(target, 'category', '')
        if isinstance(target, _Declaration):
            labels.add(category + (' implementation' if target.implementation else ' type'))
        if 'all' in allowed or allowed & labels:
            return True
        # A classifier-qualified owner applies to its instances and extensions
        # (§11.1.2 S3), not only to one exactly spelled declaration name.
        classifier = target if isinstance(target, _Declaration) else None
        if isinstance(target, _Child) and target.classifier:
            classifier = self.resolve(owner, target.classifier, None, target.node)
            if classifier is None:
                return True  # resolve already recorded the outstanding dependency
        if classifier is not None:
            for name in allowed:
                if '::' not in name:
                    continue
                base = self.symbols.get(name)
                if base is None:
                    self.pending('property.owner_defined', f'Define property owner classifier {name}', node, '§11.1.2 N4')
                    return True
                candidates = [classifier]
                if classifier.implementation:
                    component_type = self.symbols.get(classifier.key.rsplit('.', 1)[0])
                    if component_type is None:
                        self.pending('property.owner_type_defined', f'Define component type for {classifier.key}', node, '§11.1.2 S3')
                        return True
                    candidates.append(component_type)
                for current in candidates:
                    visited = set()
                    while current.key not in visited:
                        if current.key == base.key:
                            return True
                        visited.add(current.key)
                        if not current.extension:
                            break
                        current = self.resolve(current, current.extension, None, current.node)
                        if current is None:
                            return True  # a future ancestor may fulfill applies-to
        return False

    def path(self, owner, value, node, interface_request=None):
        """Resolve containment paths against inherited environments, never text order."""
        raw_parts = re.split(r'\.(?![^\[]*\])', value)
        selections = [re.findall(r'\[([^]]*)\]', part) for part in raw_parts]
        value = re.sub(r'\[[^]]*\]', '', value)
        parts = value.split('.')
        if len(parts) == 2 and parts[0] in {'self', 'processor'}:
            members = self.inherited(owner, 'internal' if parts[0] == 'self' else 'processor')
            member = members.get(parts[1])
            if member is not None:
                return member
            if _complete(owner.node):
                self.conflict('feature.proxy_defined', f'No declared {parts[0]} feature {parts[1]}', node, '§8.9 N1')
            else:
                self.pending('feature.proxy_defined', f'Declare {value}', node, '§8.9 N1')
            return None
        if parts and parts[0] == 'self':
            parts = parts[1:]
        current = owner
        for index, part in enumerate(parts):
            if isinstance(current, _Declaration):
                features = self.inherited(current, 'features')
                children = self.inherited(current, 'children')
                found = features.get(part) or children.get(part)
                if found is None:
                    # §8.9/§12 N3 permit internal and processor features to be
                    # referenced without their optional self./processor. prefix.
                    found = self.inherited(current, 'internal').get(part) or self.inherited(current, 'processor').get(part)
                if found is None:
                    if part in current.connections:
                        found = current.connections[part]
                    elif part in current.flows:
                        found = current.flows[part]
                    elif part in current.modes:
                        found = current.modes[part]
                    elif part in self.inherited(current, 'calls'):
                        found = self.inherited(current, 'calls')[part]
                    elif _complete(current.node):
                        # A still-unresolved extension can add inherited names.
                        if current.extension and self.qualified(current.package, current.extension, current.node) not in self.symbols:
                            self.pending('path.inherited_target', f'Resolve inherited path {current.key}.{part}', node, '§4.3')
                        else:
                            self.conflict('path.member_defined', f'No member {current.key}.{part}', node, '§4.3')
                    else:
                        self.pending('path.member_defined', f'Define member {current.key}.{part}', node, '§4.3')
                    if found is None:
                        return None
                current = found
                if isinstance(current, (_Child, _Feature)) and index < len(selections) and selections[index]:
                    self.check_selected_dimensions(current, selections[index], owner, node)
            else:
                self.conflict('path.structure', f'Cannot descend through {value}', node, '§4.3')
                return None
            if index < len(parts) - 1:
                if isinstance(current, (_Child, _Feature)) and current.classifier:
                    target = self.resolve(owner, current.classifier, None, node)
                    if target is None:
                        if interface_request is not None:
                            key = self.qualified(owner.package, current.classifier, owner.node).split('.', 1)[0]
                            self.request_interface(key, parts[index + 1], *interface_request, node)
                        return None
                    current = target
                elif getattr(current, 'production', '') == 'subprogram_call':
                    # Calls are syntactic declarations whose interface is the
                    # called subprogram's signature, not a classifier field on
                    # the call node itself.
                    current = self.parameter_call_target(owner, current)
                    if current is None:
                        self.pending('calls.parameter_signature', f'Resolve the called interface of {part}', node, '§5.2')
                        return None
                else:
                    self.conflict('path.structure', f'Member {part} has no classifier supporting nested path {value}', node, '§4.3')
                    return None
        return current

    def request_interface(self, classifier, name, directions, kinds, node):
        key = (classifier, name)
        domain = self.interfaces.setdefault(key, {'directions': set(DIRECTIONS), 'kinds': set(PORT_KINDS)})
        if directions is not None:
            domain['directions'] &= set(directions)
        if kinds is not None:
            domain['kinds'] &= set(kinds)
        if not domain['directions'] or not domain['kinds']:
            self.conflict('interface.obligations_compatible', f'Incompatible interface obligations for {classifier}.{name}', node, '§8/§9')
        return key

    def connection_endpoint(self, owner, value, source, bidirectional, kind, node):
        if kind == 'parameter':
            endpoint = self.parameter_endpoint(owner, value, node)
            return (endpoint[0], None, endpoint[1] is None) if endpoint is not None else (None, None, True)
        parts = re.sub(r'\[[^]]*\]', '', value).split('.')
        children = self.inherited(owner, 'children')
        boundary = len(parts) == 1 or parts[0] not in children and parts[0] not in {'self', 'processor'}
        directions = {'in out'} if bidirectional else (
            {'in', 'in out'} if source == boundary else {'out', 'in out'})
        wanted = set(PORT_KINDS) if kind == 'port' else None
        found = self.path(owner, value, node, (directions, wanted) if kind == 'port' else None)
        key = None
        ordinary_port = found is None or isinstance(found, _Feature) and found.kind in PORT_KINDS + ('port',)
        if kind == 'port' and ordinary_port:
            if boundary:
                classifier = owner.key.rsplit('.', 1)[0] if owner.implementation else owner.key
                key = self.request_interface(classifier, parts[-1], directions, wanted, node)
            elif parts[0] in children and children[parts[0]].classifier:
                classifier = self.qualified(owner.package, children[parts[0]].classifier, owner.node).rsplit('.', 1)[0]
                key = self.request_interface(classifier, parts[-1], directions, wanted, node)
        if found is not None:
            if kind == 'port':
                data_endpoint = isinstance(found, _Child) and found.category == 'data' or isinstance(found, _Feature) and found.kind == 'data access'
                self.require(ordinary_port or data_endpoint, 'connection.endpoint_kind',
                             f'{value} is not a port or data component/access', node, '§9.2')
                if isinstance(found, _Feature) and found.kind == 'data access':
                    rights = self.access_capabilities(owner, found, node)
                    if rights is not None:
                        self.require(('read' if source else 'write') in rights, 'connection.data_access_right',
                                     'Port connection data access lacks the required read/write capability', node, '§9.2 L3')
                if isinstance(found, _Feature) and ordinary_port:
                    self.require(found.direction in directions, 'connection.endpoint_direction',
                                 f'{value} has incompatible direction {found.direction}', node, '§9')
                    if key is not None and found.kind != 'port':
                        self.request_interface(key[0], key[1], {found.direction}, {found.kind}, node)
            elif kind == 'parameter':
                self.require(isinstance(found, _Child) and found.category == 'data' or
                             isinstance(found, _Feature) and found.kind in {'parameter', 'data port', 'event data port', 'data access'},
                             'connection.endpoint_kind', f'{value} cannot participate in a parameter connection', node, '§9.3')
            elif kind == 'feature group':
                self.require(isinstance(found, _Feature) and found.kind == 'feature group', 'connection.endpoint_kind',
                             f'{value} is not a feature group', node, '§9')
            elif 'access' in kind:
                expected = kind.removesuffix(' access') if kind != 'access' else ''
                labels = _owner_labels(found)
                self.require(isinstance(found, _Child) or isinstance(found, _Feature) and found.kind.endswith(' access'),
                             'connection.access_endpoint', f'{value} is not an access feature/subcomponent', node, '§9')
                if expected:
                    self.require(expected in labels or expected + ' access' in labels, 'connection.access_category',
                                 f'{value} has incompatible access category', node, '§9')
        return found, key, boundary

    def check_connection(self, owner, node):
        body = _first(node, 'connection_body')
        if body is None:
            if _first(node, 'refinement') is not None and _complete(node):
                name = _field(node, 'declaration_name')
                if not owner.extension:
                    self.conflict('connection.refinement', f'Connection {name} has no extended implementation', node, '§9')
            return
        source_node, target_node = _first(body, 'source_endpoint'), _first(body, 'destination_endpoint')
        kind_node, arrow_node = _first(body, 'connection_kind'), _first(body, 'connection_arrow')
        if not _complete(kind_node) or not _complete(arrow_node):
            return
        kind = _text(kind_node)
        bidirectional = _text(arrow_node) == '<->'
        # A completed arrow fixes the source's role even while the destination
        # identifier is still growing. Check each completed endpoint in its
        # existing lexical/inherited scope; unresolved references remain Pending.
        # Pair constraints and edges still require both endpoint fields. Keeping
        # this split inside the rule package preserves language-neutral parsing
        # and the runtime's existential treatment of partial parse alternatives.
        source_result = (self.connection_endpoint(owner, _name(source_node), True, bidirectional, kind, source_node)
                         if _complete(source_node) else None)
        target_result = (self.connection_endpoint(owner, _name(target_node), False, bidirectional, kind, target_node)
                         if _complete(target_node) else None)
        if source_result is None or target_result is None:
            return
        left, lk, lb = source_result
        right, rk, rb = target_result
        if kind == 'port':
            # §9.2(4) permits sibling links and mappings up/down containment,
            # not a wire directly between two ports of the same boundary.
            if isinstance(left, _Feature) and isinstance(right, _Feature):
                self.require(not (lb and rb and left.kind in PORT_KINDS and right.kind in PORT_KINDS),
                             'connection.distinct_component_boundary',
                             'A port connection cannot directly connect two ports of its containing component', node, '§9.2(4)')
            if lk and rk:
                self.edges.append((lk, rk, bidirectional, node))
            if left is not None and right is not None:
                def port_kind(value):
                    return 'data port' if isinstance(value, _Child) or isinstance(value, _Feature) and value.kind == 'data access' else getattr(value, 'kind', '')
                left_kind, right_kind = port_kind(left), port_kind(right)
                if 'port' not in {left_kind, right_kind}:
                    self.require(right_kind in PORT_DESTINATIONS.get(left_kind, set()), 'connection.port_kinds',
                                 f'Incompatible port kinds {left_kind} -> {right_kind}', node, '§9')
                if bidirectional:
                    self.require(left_kind == right_kind, 'connection.bidirectional_kind',
                                 'Bidirectional port kinds must match', node, '§9')
                if getattr(left, 'classifier', '') and getattr(right, 'classifier', '') and 'event port' not in {left_kind, right_kind}:
                    self.connection_classifier_match(owner, left, right, node)
                    if bidirectional:
                        self.connection_classifier_match(owner, right, left, node)
            if any(_name(endpoint).startswith('processor.') for endpoint in (source_node, target_node)):
                self.require(owner.category in {'thread', 'subprogram'}, 'connection.processor_context',
                             'Processor port connections belong to threads or subprograms', node, '§9.2 L16')
                concrete = [v for v in (left, right) if isinstance(v, _Feature) and v.kind != 'port']
                self.require(all(v.kind in {'event port', 'event data port'} for v in concrete),
                             'connection.processor_event', 'Processor ports participate in event connections', node, '§9.2 L16')
        if kind != 'port' and left is not None and right is not None:
            self.check_nonport_pair(owner, left, right, lb, rb, kind, bidirectional, node)
        for prop in _section_items(node, 'inline_properties', 'property_association'):
            self.check_property(prop, owner, node)

    def parameter_call_target(self, owner, call):
        """Resolve the called signature without inventing a subprogram instance.

        A dotted classifier implementation and a call through a local access
        have different meanings. Resolve existing declarations/members first;
        only an unresolved classifier becomes a forward-reference obligation.
        """
        reference = _field(call, 'called_subprogram')
        if not reference or not _complete(_first(call, 'called_subprogram')):
            return None
        calls = self.inherited(owner, 'calls')
        features, children = self.inherited(owner, 'features'), self.inherited(owner, 'children')
        head, dot, tail = reference.partition('.')
        local = features.get(head) or children.get(head)
        if local is not None or head == 'processor':
            target = self.path(owner, reference, call)
            if target is None:
                return None
            allowed = isinstance(target, _Child) and target.category == 'subprogram' or isinstance(target, _Feature) and target.kind in {'subprogram access', 'subprogram'}
            self.require(allowed, 'calls.target_category', 'A call must identify a subprogram or subprogram access', call, '§5.2 N3–N6')
            if isinstance(target, _Feature) and not dot:
                self.require(target.direction == 'requires', 'calls.requires_access', 'An unqualified called access must be requires subprogram access', call, '§5.2 N6')
            if dot and isinstance(local, _Feature):
                if local.kind == 'feature group' and isinstance(target, _Feature):
                    self.require(target.direction == 'requires', 'calls.feature_group_access', 'A feature-group call must name requires subprogram access', call, '§5.2')
                elif local.kind == 'subprogram group access':
                    self.require(local.direction == 'requires' and isinstance(target, _Feature) and target.direction == 'provides',
                                 'calls.group_access', 'Calls through a subprogram group require its access and select a provided subprogram', call, '§5.2')
            if dot and isinstance(local, _Child):
                self.require(local.category == 'subprogram group', 'calls.instance_context',
                             'A nested instance call must name a subprogram-group subcomponent', call, '§5.2')
            if allowed and target.classifier:
                scope = next((v for v in self.symbols.values() if any(x is target for x in v.features.values()) or any(x is target for x in v.children.values())), owner)
                return self.resolve(scope, target.classifier, {'subprogram'}, call)
            return None
        key = self.qualified(owner.package, reference, call)
        if key in self.symbols:
            return self.resolve(owner, reference, {'subprogram'}, call)
        if dot:
            context = self.symbols.get(self.qualified(owner.package, head, call))
            if context is not None and context.category in {'data', 'subprogram group', 'abstract'}:
                target = self.path(context, tail, call)
                if isinstance(target, _Feature):
                    self.require(target.kind == 'subprogram access' and target.direction == 'provides',
                                 'calls.classifier_access', 'A classifier-scoped call must name provides subprogram access', call, '§5.2')
                    return self.resolve(context, target.classifier, {'subprogram'}, call) if target.classifier else None
                if target is not None:
                    self.conflict('calls.classifier_access', 'Classifier-scoped call does not name a subprogram access', call, '§5.2')
                return None
        prototype = self.inherited(owner, 'prototypes').get(reference)
        if prototype is not None:
            formal = _first(prototype, 'component_prototype', recursive=True)
            if formal is not None:
                self.require(_field(formal, 'category') == 'subprogram', 'calls.prototype_category', 'A called prototype must be subprogram', call, '§5.2')
                classifier = _field(formal, 'classifier_reference')
                if classifier:
                    return self.resolve(owner, classifier, {'subprogram'}, call)
            return None
        return self.resolve(owner, reference, {'subprogram'}, call)

    def parameter_sequences(self, owner, active=()):
        if owner.key in active:
            return []
        result = []
        if owner.extension:
            parent = self.symbols.get(self.qualified(owner.package, owner.extension, owner.node))
            if parent is not None:
                result.extend(self.parameter_sequences(parent, active + (owner.key,)))
        result.extend(_section_items(owner.node, 'calls', 'call_sequence'))
        return result

    def parameter_endpoint(self, owner, value, node):
        """Return (member, call-name-or-None, context kind, direction).

        The direction of an aggregate field is inherited from its enclosing
        parameter/port; a data subcomponent does not have a fabricated direction.
        """
        parts = re.sub(r'\[[^]]*\]', '', value).split('.')
        calls = self.inherited(owner, 'calls')
        if parts[0] in calls:
            self.require(len(parts) == 2, 'parameter.call_path', 'A called parameter is referenced as call.parameter', node, '§9.3')
            target = self.parameter_call_target(owner, calls[parts[0]])
            if target is None:
                self.pending('calls.parameter_signature', f'Resolve the parameter signature of {parts[0]}', node, '§5.2/§9.3')
                return None
            found = self.path(target, '.'.join(parts[1:]), node) if len(parts) > 1 else None
            if found is not None:
                self.require(isinstance(found, _Feature) and found.kind == 'parameter', 'parameter.call_endpoint', 'Only a parameter of the called subprogram is a parameter connection endpoint', node, '§9.3 N2')
                return found, parts[0], 'call', getattr(found, 'direction', '')
            return None
        found = self.path(owner, value, node)
        if found is None:
            return None
        root = self.inherited(owner, 'features').get(parts[0]) or self.inherited(owner, 'children').get(parts[0])
        if isinstance(root, _Child) and (root.category != 'data' or len(parts) != 1):
            self.conflict('parameter.context', 'A subprogram parameter must be addressed through a call, not through a subcomponent', node, '§9.3 N2')
        allowed = isinstance(found, _Child) and found.category == 'data' or isinstance(found, _Feature) and found.kind in {'parameter', 'data port', 'event data port', 'data access', 'feature group'}
        self.require(allowed, 'parameter.endpoint_kind', f'{value} cannot be a parameter connection endpoint', node, '§9.3')
        if isinstance(found, _Feature):
            kind, direction = found.kind, found.direction
            if kind == 'data access':
                self.require(direction == 'requires', 'parameter.requires_data_access', 'Boundary data access must be requires', node, '§9.3 N2')
            if kind == 'feature group':
                signature = self.featuregroup_signature(owner, found, node)
                if signature is not None:
                    self.require(not signature, 'parameter.incomplete_group', 'A whole feature group parameter endpoint must be incomplete', node, '§9.3 L2')
        else:
            kind, direction = 'data', ''
        if isinstance(root, _Feature) and root.kind in {'parameter', 'data port', 'event data port'} and len(parts) > 1:
            self.require(isinstance(found, _Child) and found.category == 'data', 'parameter.aggregate_field', 'A parameter/port field reference must select a data subcomponent', node, '§9.3')
            kind, direction = root.kind, root.direction
        return found, None, kind, direction

    def check_parameter_connection(self, owner, node):
        body = _first(node, 'connection_body')
        if body is None:
            return
        self.require(_text(_first(body, 'connection_arrow')) == '->', 'parameter.directional', 'Parameter connections must be directional', node, '§9.3')
        source_node, destination_node = _first(body, 'source_endpoint'), _first(body, 'destination_endpoint')
        left = self.parameter_endpoint(owner, _name(source_node), source_node)
        right = self.parameter_endpoint(owner, _name(destination_node), destination_node)
        if left is None or right is None:
            return
        a, acall, akind, adir = left
        b, bcall, bkind, bdir = right
        self.require(acall is not None or bcall is not None, 'parameter.call_context', 'A parameter connection must involve a subprogram call', node, '§9.3 L2')
        for is_source, call, kind, direction in ((True, acall, akind, adir), (False, bcall, bkind, bdir)):
            if kind in {'call', 'parameter', 'data port', 'event data port'}:
                wanted = ('out' if is_source else 'in') if call else ('in' if is_source else 'out')
                self.require(direction in {wanted, 'in out'}, 'parameter.endpoint_direction',
                             f'Parameter connection endpoint requires {wanted}, found {direction}', node, '§9.3 L1')
        self.connection_classifier_match(owner, a, b, node)
        self.require(akind != 'feature group', 'parameter.incomplete_group_direction',
                     'An incomplete feature group is only a destination of a called parameter', node, '§9.3 L2')
        for member, required in ((a, 'read'), (b, 'write')):
            if isinstance(member, _Feature) and member.kind == 'data access':
                capabilities = self.access_capabilities(owner, member, node)
                if capabilities is not None:
                    self.require(required in capabilities, 'parameter.access_right',
                                 f'Parameter data access requires {required} capability', node, '§9.3 L1')
        sequences = self.parameter_sequences(owner)
        occurrences = {}
        universe = set(self.inherited(owner, 'modes')) or {'<default>'}
        for sequence in sequences:
            for index, call in enumerate(_direct(sequence, 'subprogram_call')):
                occurrences.setdefault(_field(call, 'declaration_name'), []).append((sequence, index))
        if _complete(node):
            active_modes = self.membership(node, universe)
            for name in (acall, bcall):
                if name and name in occurrences:
                    applicable = set().union(*(self.membership(seq, universe) for seq, _ in occurrences[name]))
                    self.require(active_modes <= applicable, 'parameter.call_modes', f'Connection is active when call {name} is absent', node, '§9.3/§12')
            if acall and bcall:
                ordered = any(sa is sb and ia < ib for sa, ia in occurrences.get(acall, ()) for sb, ib in occurrences.get(bcall, ()))
                self.require(ordered, 'parameter.call_order', 'Source call must precede destination call in the same sequence', node, '§9.3 N2/(6)')
            # Incoming multiplicity is checked on complete associations only:
            # a still-open association can acquire a disjoint in-modes clause.
            destination = _name(destination_node)
            for other in self.inherited(owner, 'connections').values():
                if other is node or not _complete(other):
                    continue
                other_body = _first(other, 'connection_body')
                if other_body and _text(_first(other_body, 'connection_kind')) in {'parameter', 'feature'} and _field(other_body, 'destination_endpoint') == destination:
                    self.require(not (active_modes & self.membership(other, universe)), 'parameter.single_incoming',
                                 f'Multiple incoming parameter connections to {destination} in overlapping modes', node, '§9.3 L3')

    def check_parameter_calls(self, owner):
        sequences = self.parameter_sequences(owner)
        universe = set(self.inherited(owner, 'modes')) or {'<default>'}
        names = set()
        for sequence in sequences:
            name = _field(sequence, 'declaration_name')
            if _complete(_first(sequence, 'declaration_name')):
                self.require(name not in names, 'calls.unique_sequence', f'Duplicate call sequence {name}', sequence, '§5.2 N1')
                names.add(name)
                for attribute in ('features', 'children', 'connections', 'modes', 'transitions',
                                  'flows', 'end_to_end', 'prototypes', 'calls'):
                    self.require(name not in self.inherited(owner, attribute), 'calls.sequence_namespace',
                                 f'Call sequence {name} conflicts with a local member', sequence, '§5.2 N1')
        if owner.category == 'subprogram':
            for index, sequence in enumerate(sequences):
                if not _complete(sequence):
                    continue
                for other in sequences[:index]:
                    if _complete(other):
                        self.require(not (self.membership(sequence, universe) & self.membership(other, universe)), 'calls.one_sequence_per_mode',
                                     'Only one subprogram call sequence may apply to a mode', sequence, '§5.2 L3')
        for call in self.inherited(owner, 'calls').values():
            if _complete(call):
                self.parameter_call_target(owner, call)

    def connection_classifier_match(self, owner, left, right, node):
        """Apply the selected classifier rule and its real project constant table.

        References are resolved in their declaring package, not whichever package
        happens to contain the connection. This matters for imported interfaces.
        """
        if not left.classifier or not right.classifier:
            return
        rule = self.connection_matching_rule(owner, node)
        def declaring(feature):
            for candidate in self.symbols.values():
                if any(value is feature for value in candidate.features.values()) or any(value is feature for value in candidate.children.values()):
                    return candidate
            return owner
        a = self.resolve(declaring(left), left.classifier, None, left.node)
        b = self.resolve(declaring(right), right.classifier, None, right.node)
        if a and b:
            compatible = a.key == b.key or (a.implementation and not b.implementation and a.key.rsplit('.', 1)[0] == b.key)
            table = {'equivalence': 'supported_classifier_equivalence_matches',
                     'subset': 'supported_classifier_subset_matches',
                     'conversion': 'supported_type_conversions'}.get(rule)
            if table:
                pairs = self.classifier_pairs(owner, table, node)
                if pairs is None:
                    return
                compatible |= (a.key, b.key) in pairs or rule == 'equivalence' and (b.key, a.key) in pairs
                if rule == 'subset' and a.category == b.category == 'data' and a.implementation and b.implementation:
                    compatible |= set(self.inherited(b, 'children')) <= set(self.inherited(a, 'children'))
            elif rule != 'classifier_match':
                return  # The policy-selection function already emitted a diagnostic.
            self.require(compatible, 'connection.classifier_match',
                         f'{rule} fails: {a.key} versus {b.key}', node, '§9.2/§9.4')

    def classifier_pairs(self, owner, name, node):
        constant = self.constants.get('aadl_project::' + name)
        if constant is None:
            self.pending('connection.matching_policy_table', f'Provide AADL_Project::{name}', node, '§9.2 L14')
            return None
        words = constant['value']
        if not words or words[0] != '(' or words[-1] != ')':
            self.pending('connection.matching_policy_table', f'Evaluate classifier pair table {name}', node, '§9.2 L14')
            return None
        pairs = set()
        for pair in _split_top(words[1:-1], ','):
            if not pair:
                continue
            if pair[0] != '(' or pair[-1] != ')':
                self.conflict('connection.matching_policy_table', f'{name} must contain classifier pairs', node, '§9.2 L14')
                continue
            elements = _split_top(pair[1:-1], ',')
            values = []
            for element in elements:
                if element[:2] != ['classifier', '('] or element[-1:] != [')']:
                    self.conflict('connection.matching_policy_table', f'{name} pair contains a non-classifier value', node, '§9.2 L14')
                    break
                reference = ''.join(element[2:-1])
                values.append(self.qualified(owner.package, reference, node))
            if len(values) == 2:
                pairs.add(tuple(values))
        return pairs

    def connection_matching_rule(self, owner, node):
        # This rule selection is explicit: a requested Equivalence/Subset rule
        # must never be silently evaluated as the default Classifier_Match.
        properties = _find(node, 'property_association') + _section_items(owner.node, 'properties', 'property_association')
        for prop in properties:
            if _field(prop, 'property_name').split('::')[-1] in {'classifier_matching_rule', 'classifier_matching_rules'}:
                applies = _first(prop, 'applies_to')
                if applies is not None and _field(node, 'declaration_name') not in {_name(v) for v in _find(applies, 'contained_path')}:
                    continue
                rule = _text(_first(prop, 'assignment'))
                if rule not in {'classifier_match', 'equivalence', 'subset', 'conversion'}:
                    self.pending('connection.classifier_matching_rule', f'Evaluate matching policy {rule}', prop, '§9.2/§9.5')
                return rule
        return 'classifier_match'

    def access_capabilities(self, owner, feature, node):
        """Evaluate a non-modal Access_Right with inherited/contained precedence."""
        capabilities = {'read_only': frozenset({'read'}), 'write_only': frozenset({'write'}),
                        'read_write': frozenset({'read', 'write'}), 'by_method': frozenset({'method'})}
        declaring = next((candidate for candidate in self.symbols.values() if
                          any(v is feature for v in candidate.features.values()) or
                          any(v is feature for v in candidate.children.values())), owner)
        paths = {feature.name} if declaring is owner or any(v is feature for v in self.inherited(owner, 'features').values()) else set()
        for child in self.inherited(owner, 'children').values():
            if not child.classifier:
                continue
            target = self.symbols.get(self.qualified(owner.package, child.classifier, owner.node))
            if target and any(v is feature for v in self.inherited(target, 'features').values()):
                paths.add(child.name + '.' + feature.name)
        levels = []
        for scope, targets in ((owner, paths), (declaring, {feature.name})):
            visited = set()
            while scope is not None and scope.key not in visited:
                visited.add(scope.key)
                matches = []
                for prop in _section_items(scope.node, 'properties', 'property_association'):
                    applies = _first(prop, 'applies_to')
                    if applies is not None and targets & {_name(v) for v in _find(applies, 'contained_path')}:
                        matches.append(prop)
                levels.append(matches)
                scope = self.symbols.get(self.qualified(scope.package, scope.extension, scope.node)) if scope.extension else None
        levels.insert(1, list(_find(feature.node, 'property_association')))
        for level in levels:
            for prop in level:
                if _field(prop, 'property_name').split('::')[-1] != 'access_right':
                    continue
                assignment = _first(prop, 'assignment')
                values = {_text(_first(value, 'property_value')) for value in _find(assignment, 'modal_value')}
                if len(values) != 1 or next(iter(values), '') not in capabilities:
                    self.pending('property.access_right_evaluation', 'Evaluate mode-specific or computed effective Access_Right', prop, '§9.4 L10')
                    return None
                return capabilities[next(iter(values))]
        return capabilities['read_write']

    def check_nonport_pair(self, owner, left, right, lb, rb, kind, bidirectional, node):
        """Check the default core access/feature-group connection rules.

        Complete connectivity across instantiated systems remains a separate
        check. An unresolved endpoint is already Pending in path()/resolve().
        """
        if kind == 'parameter' or kind == 'feature' and any(isinstance(v, _Feature) and v.kind == 'parameter' for v in (left, right)):
            self.check_parameter_connection(owner, node)
            return
        if kind == 'feature group':
            if isinstance(left, _Feature) and isinstance(right, _Feature):
                a = self.featuregroup_signature(owner, left, node)
                b = self.featuregroup_signature(owner, right, node)
                if a is not None and b is not None:
                    self.require(self.connection_directions_match(left.direction, right.direction, lb, rb, bidirectional),
                                 'connection.feature_group_direction', 'Feature-group directions conflict with the connection', node, '§9.5 L5–L8')
                    if not bidirectional:
                        self.require(self.featuregroup_allows_direction(a, 'in' if lb else 'out') and
                                     self.featuregroup_allows_direction(b, 'out' if rb else 'in'),
                                     'connection.feature_group_element_direction', 'A directional group connection contradicts a contained feature direction', node, '§9.5 L8')
                    sibling = not lb and not rb
                    if self.connection_matching_rule(owner, node) != 'classifier_match':
                        if not self.featuregroup_signatures_match(a, b, sibling):
                            self.connection_classifier_match(owner, left, right, node)
                        return
                    self.require(self.featuregroup_signatures_match(a, b, sibling),
                                 'connection.feature_group_complement' if sibling else 'connection.feature_group_mapping',
                                 'Feature-group signatures do not have the required corresponding directions/classifiers', node, '§8.2/§9.5')
                    if not sibling and left.classifier and right.classifier:
                        self.connection_classifier_match(owner, left, right, node)
            return
        if 'access' in kind:
            def category(value):
                return value.category if isinstance(value, _Child) else value.kind.removesuffix(' access')
            a, b = category(left), category(right)
            self.require(a == b, 'connection.access_category', f'Access categories differ: {a} and {b}', node, '§9.4 L1')
            self.require(a in {'data', 'bus', 'virtual bus', 'subprogram', 'subprogram group'},
                         'connection.access_category', f'{a} is not an accessible component category', node, '§9.4')
            if a in {'subprogram', 'subprogram group'}:
                self.require(bidirectional, 'connection.access_bidirectional', 'Subprogram access connections must be bidirectional', node, '§9.4(1)')
            if isinstance(left, _Feature) and isinstance(right, _Feature):
                expected = left.direction != right.direction if lb == rb else left.direction == right.direction
                self.require(expected, 'connection.access_provider_requirer',
                             'Sibling accesses require opposite provides/requires; hierarchical mappings preserve it', node, '§9.4 L5–L7')
            elif isinstance(left, _Child) and isinstance(right, _Child):
                self.conflict('connection.access_provider_count', 'An access connection cannot connect two accessed subcomponents', node, '§9.4 L8')
            else:
                feature, boundary = (right, rb) if isinstance(left, _Child) else (left, lb)
                self.require(feature.direction == ('provides' if boundary else 'requires'),
                             'connection.access_provider_requirer', 'An accessed subcomponent must map to an enclosing provides or a child requires access', node, '§9.4 L6–L7')
            # Provider->requirer controls classifier compatibility independently
            # of the written arrow, whose direction represents read/write flow.
            provider, requirer = left, right
            if isinstance(right, _Child) or isinstance(right, _Feature) and right.direction == 'provides':
                provider, requirer = right, left
            self.connection_classifier_match(owner, provider, requirer, node)
            offered = self.access_capabilities(owner, provider, node)
            requested = self.access_capabilities(owner, requirer, node)
            if offered is not None and requested is not None:
                self.require(requested <= offered, 'connection.access_capabilities',
                             'Required access rights exceed provided access rights', node, '§9.4 L10')
                if not bidirectional:
                    if requested == {'read'}:
                        self.require(left is provider, 'connection.access_read_direction', 'Read access flows from provider to requirer', node, '§9.4 L3')
                    elif requested == {'write'}:
                        self.require(right is provider, 'connection.access_write_direction', 'Write access flows from requirer to provider', node, '§9.4 L3')
            return
        if kind == 'feature':
            self.require(isinstance(left, _Feature) and isinstance(right, _Feature),
                         'connection.feature_endpoint', 'Feature connections require feature endpoints', node, '§9.1')
            if not isinstance(left, _Feature) or not isinstance(right, _Feature):
                return
            if 'feature' in {left.kind, right.kind}:
                self.require(self.connection_directions_match(left.direction, right.direction, lb, rb, bidirectional),
                             'connection.feature_direction', 'Feature direction conflicts with the connection', node, '§9.1 L2–L6')
                if 'feature group' in {left.kind, right.kind}:
                    abstract = left if left.kind == 'feature' else right
                    self.require(not abstract.classifier, 'connection.abstract_group_classifier',
                                 'An abstract feature connected to a feature group must have no classifier', node, '§9.1 L13')
                elif left.classifier and right.classifier:
                    for feature in (left, right):
                        declaration_scope = next((candidate for candidate in self.symbols.values()
                                                  if any(value is feature for value in candidate.features.values())), owner)
                        self.resolve(declaration_scope, feature.classifier, {'data', 'bus'}, node)
                    self.connection_classifier_match(owner, left, right, node)
                return
            concrete = 'feature group' if left.kind == right.kind == 'feature group' else ('access' if left.kind.endswith(' access') and right.kind.endswith(' access') else '')
            if concrete:
                self.check_nonport_pair(owner, left, right, lb, rb, concrete, bidirectional, node)
            elif left.kind in PORT_KINDS and right.kind in PORT_KINDS:
                self.require(self.connection_directions_match(left.direction, right.direction, lb, rb, bidirectional),
                             'connection.feature_port_direction', 'Concrete port feature directions are incompatible', node, '§9.1 L8–L10')
                self.require(right.kind in PORT_DESTINATIONS[left.kind] and (not bidirectional or left.kind == right.kind),
                             'connection.feature_port_kind', 'Concrete port feature kinds are incompatible', node, '§9.1 L8–L10')
                if 'event port' not in {left.kind, right.kind}:
                    self.connection_classifier_match(owner, left, right, node)
                    if bidirectional:
                        self.connection_classifier_match(owner, right, left, node)
            else:
                self.conflict('connection.concrete_feature_kind', 'Concrete feature endpoint kinds are incompatible', node, '§9.1 L8–L10')

    @staticmethod
    def connection_directions_match(left, right, lb, rb, bidirectional):
        def supports(actual, expected):
            return actual not in {'in', 'out', 'in out'} or actual in {expected, 'in out'}
        forward = supports(left, 'in' if lb else 'out') and supports(right, 'out' if rb else 'in')
        reverse = supports(left, 'out' if lb else 'in') and supports(right, 'in' if rb else 'out')
        return forward or bidirectional and reverse

    @staticmethod
    def featuregroup_allows_direction(signature, expected):
        return all(_Check.featuregroup_allows_direction(payload, expected) if kind == 'feature group' else
                   direction not in {'in', 'out', 'in out'} or direction in {expected, 'in out'}
                   for kind, direction, payload in signature)

    def featuregroup_signature(self, owner, feature, node, active=()):
        if not feature.classifier:
            # An incomplete feature group is allowed by the core language; its
            # absent signature must not be invented as an incompatible type.
            return ()
        declaration_scope = next((candidate for candidate in self.symbols.values()
                                  if any(value is feature for value in candidate.features.values())), owner)
        target = self.resolve(declaration_scope, feature.classifier, {'feature group'}, node, False)
        if target is None:
            return None
        if feature.inverse:
            self.require(_first(target.node, 'inverse_clause') is None, 'feature_group.declaration_inverse_chain',
                         'An inverse feature declaration cannot reference an inverse feature-group type', node, '§8.2 L6')
        return self.featuregroup_type_signature(target, node, feature.inverse, active)

    def featuregroup_type_signature(self, target, node, flip=False, active=()):
        if target.key in active:
            self.conflict('feature_group.recursive_signature', 'Recursive feature-group nesting/inversion', node, '§8.2')
            return None
        active = active + (target.key,)
        members = list(self.inherited(target, 'features').values())
        inverse = _first(target.node, 'inverse_clause')
        if inverse is not None:
            ref = _field(inverse, 'classifier_reference')
            base = self.resolve(target, ref, {'feature group'}, inverse, False)
            if base is None:
                return None
            self.require(_first(base.node, 'inverse_clause') is None, 'feature_group.inverse_chain',
                         'A feature-group inverse cannot itself be an inverse', inverse, '§8.2 L2')
            if not members:
                return self.featuregroup_type_signature(base, node, not flip, active)
        if not _complete(target.node):
            self.pending('feature_group.signature_closed', f'Complete interface {target.key}', node, '§8.2')
            return None
        result = []
        for member in members:
            direction = member.direction
            if flip:
                direction = {'in': 'out', 'out': 'in', 'requires': 'provides', 'provides': 'requires'}.get(direction, direction)
            if member.kind == 'feature group':
                nested = self.featuregroup_signature(target, member, node, active)
                if nested is None:
                    return None
                if flip:
                    nested = self.featuregroup_flip(nested)
                result.append(('feature group', direction, nested))
            else:
                classifier = self.qualified(target.package, member.classifier, node) if member.classifier else ''
                result.append((member.kind, direction, classifier))
        return tuple(result)

    @staticmethod
    def featuregroup_flip(signature):
        return tuple((kind, {'in': 'out', 'out': 'in', 'requires': 'provides', 'provides': 'requires'}.get(direction, direction),
                      _Check.featuregroup_flip(payload) if kind == 'feature group' else payload)
                     for kind, direction, payload in signature)

    @staticmethod
    def featuregroup_signatures_match(left, right, complement):
        if len(left) != len(right):
            return False
        for (ak, ad, av), (bk, bd, bv) in zip(left, right):
            if ak != bk:
                if not complement or ak not in PORT_KINDS or bk not in PORT_KINDS:
                    return False
                if ad == 'out' and bk not in PORT_DESTINATIONS.get(ak, ()):
                    return False
                if ad == 'in' and ak not in PORT_DESTINATIONS.get(bk, ()):
                    return False
                if ad == 'in out':
                    return False
            if ak == 'feature group':
                if not _Check.featuregroup_signatures_match(av, bv, complement):
                    return False
            elif av and bv and av != bv:
                return False
            expected = {'in': 'out', 'out': 'in', 'requires': 'provides', 'provides': 'requires'}.get(ad, ad) if complement else ad
            if expected != bd:
                return False
        return True

    def check_featuregroup_declaration(self, owner):
        inverse = _first(owner.node, 'inverse_clause')
        if owner.extension:
            ancestor = self.resolve(owner, owner.extension, {'feature group'}, owner.node, False)
            if ancestor is not None:
                ancestor_inverse = _first(ancestor.node, 'inverse_clause')
                self.require(ancestor_inverse is None or bool(ancestor.features), 'feature_group.extensible',
                             'An implicit inverse feature-group type cannot be extended', owner.node, '§8.2 L3')
                self.require(ancestor_inverse is not None or inverse is None, 'feature_group.extension_inverse',
                             'An extension cannot introduce inverse of when its ancestor has none', owner.node, '§8.2 L4')
                if ancestor_inverse is not None and owner.features and _complete(owner.node):
                    self.require(inverse is not None, 'feature_group.extension_inverse',
                                 'An inverse extension adding features must identify its inverse type', owner.node, '§8.2 L5')
        if inverse is None:
            return
        base = self.resolve(owner, _field(inverse, 'classifier_reference'), {'feature group'}, inverse, False)
        if base is None:
            return
        self.require(_first(base.node, 'inverse_clause') is None, 'feature_group.inverse_chain',
                     'Inverse-of chains are forbidden', inverse, '§8.2 L2')
        if owner.features and _complete(owner.node) and _complete(base.node):
            a = self.featuregroup_type_signature(owner, inverse)
            b = self.featuregroup_type_signature(base, inverse)
            if a is not None and b is not None:
                self.require(self.featuregroup_signatures_match(a, b, True), 'feature_group.inverse_signature',
                             'Explicit inverse feature groups must be pairwise complements in declaration order', inverse, '§8.2 L8–L11')

    def is_subtype(self, child, parent, active=()):
        if child.key == parent.key:
            return True
        if child.key in active or not child.extension:
            return False
        key = self.qualified(child.package, child.extension, child.node)
        extended = self.symbols.get(key)
        return bool(extended and self.is_subtype(extended, parent, active + (child.key,)))

    def feature_allowed(self, owner, feature):
        category, kind, direction = owner.category, feature.kind, feature.direction
        if kind in {'feature', 'feature group'}:
            return True
        if kind in PORT_KINDS:
            if category == 'subprogram':
                return kind in {'event port', 'event data port'} and direction == 'out'
            return category not in {'data', 'subprogram group'}
        if kind == 'parameter':
            return category == 'subprogram'
        if category in {'abstract', 'system'}:
            return True
        if category in _SOFTWARE:
            return kind in {'data access', 'subprogram access', 'subprogram group access'}
        if category == 'data':
            return kind == 'data access' and direction == 'provides' or kind == 'subprogram access' or (
                kind == 'subprogram group access' and direction == 'requires')
        if category == 'subprogram':
            return direction == 'requires' and kind in {'data access', 'subprogram access', 'subprogram group access'}
        if category == 'subprogram group':
            return kind in {'subprogram access', 'subprogram group access'}
        if category in _PLATFORM:
            if kind in {'subprogram access', 'subprogram group access'}:
                return category in {'processor', 'virtual processor', 'device'} and direction == 'provides'
            return kind == 'virtual bus access' or kind == 'bus access' and category not in {'virtual processor', 'virtual bus'}
        return False

    def check_plan(self, owner):
        if self.plan is None:
            return
        item = self.plan.get(owner.key) or self.plan.get(owner.name)
        if item is None:
            self.conflict('plan.declaration_set', f'{owner.key} is outside the declaration plan', owner.node)
            return
        self.require(item['category'] == owner.category and (item['kind'] == 'implementation') == owner.implementation,
                     'plan.declaration_signature', f'{owner.key} differs from the declaration plan', owner.node)
        actual = {k: v for k, v in self.inherited(owner, 'features').items() if v.kind in PORT_KINDS}
        if owner.implementation:
            return
        for name, feature in actual.items():
            expected = item['ports'].get(name)
            if expected is None or any(expected[k] != getattr(feature, k) for k in ('direction', 'kind')):
                self.conflict('plan.interface', f'{owner.key}.{name} differs from planned interface', feature.node)
            elif expected['classifier'] and feature.classifier:
                a = self.qualified(owner.package, expected['classifier'], owner.node)
                b = self.qualified(owner.package, feature.classifier, owner.node)
                self.require(a == b, 'plan.interface_classifier', f'Wrong planned classifier for {name}', feature.node)
            elif _complete(feature.node) and bool(expected['classifier']) != bool(feature.classifier):
                self.conflict('plan.interface_classifier', f'Wrong planned classifier for {name}', feature.node)
        if _complete(owner.node):
            self.require(set(actual) == set(item['ports']), 'plan.interface_complete',
                         f'{owner.key} closes without all planned interfaces', owner.node)

    def check_declaration(self, owner):
        self.check_refinement_prefixes(owner)
        if owner.implementation:
            self.resolve(owner, owner.name.rsplit('.', 1)[0], {owner.category}, owner.node, False)
        if owner.extension:
            extended = self.resolve(owner, owner.extension, {owner.category, 'abstract'}, owner.node, owner.implementation)
            if owner.implementation and extended is not None:
                self.check_implementation_ancestry(owner, extended)
        features, children = self.inherited(owner, 'features'), self.inherited(owner, 'children')
        local_names = {}
        for attribute in ('features', 'children', 'connections', 'modes', 'transitions', 'flows',
                          'end_to_end', 'prototypes', 'calls', 'internal', 'processor'):
            # Namespace uniqueness includes inherited declarations (§4.3 N2/N4,
            # §4.4 N3/N4). Refinement replaces a member of its own kind; it must
            # not hide an inherited declaration from a different section.
            members = self.inherited(owner, attribute)
            for name, member in members.items():
                if name in local_names:
                    self.conflict('name.local_namespace', f'{owner.key}.{name} is declared in two sections', getattr(member, 'node', member), '§4.3')
                local_names[name] = member
        for name, child in children.items():
            if name in features:
                self.conflict('name.inherited_namespace', f'Child {name} shadows an inherited feature', child.node, '§4.3')
            self.require(child.category in CONTAINMENT.get(owner.category, ()), 'containment.category',
                         f'{owner.category} cannot contain {child.category}', child.node, '§5–§7')
            if child.classifier:
                self.resolve(owner, child.classifier, {child.category}, child.node)
            for prop in _section_items(child.node, 'inline_properties', 'property_association'):
                self.check_property(prop, owner, child)
        for feature in owner.features.values():
            self.require(self.feature_allowed(owner, feature), 'feature.owner_category',
                         f'{owner.category} cannot own {feature.direction} {feature.kind}', feature.node, '§5–§8')
            if feature.classifier:
                category = 'data' if feature.kind in PORT_KINDS + ('parameter',) else (
                    'feature group' if feature.kind == 'feature group' else feature.kind.removesuffix(' access'))
                self.resolve(owner, feature.classifier, None if feature.kind == 'feature' else {category}, feature.node)
            for prop in _section_items(feature.node, 'inline_properties', 'property_association'):
                self.check_property(prop, owner, feature)
        for feature in (*owner.internal.values(), *owner.processor.values()):
            if feature.classifier:
                category = 'subprogram' if feature.kind == 'subprogram access' else 'data'
                self.resolve(owner, feature.classifier, {category}, feature.node)
        for node in owner.connections.values():
            self.check_connection(owner, node)
        for prop in _section_items(owner.node, 'properties', 'property_association'):
            self.check_property(prop, owner)
        self.check_property_duplicates(owner)
        self.check_modes(owner)
        self.check_flows(owner)
        self.check_prototypes(owner)
        self.check_arrays(owner)
        self.check_plan(owner)
        self.require_supported_checks(owner)
        if _sections(owner.node, 'calls'):
            self.require(owner.category in {'thread', 'subprogram', 'abstract'}, 'calls.owner_category',
                         f'{owner.category} cannot own call sequences', owner.node, '§5')
        self.check_parameter_calls(owner)

    def check_implementation_ancestry(self, owner, extended):
        """Implementation inheritance must follow the component type ancestry.

        Comparing categories alone accepts unrelated system implementations.
        Resolve each type in its own package (including aliases), and leave an
        unfinished/future ancestor as an obligation rather than a false error.
        """
        current = self.resolve(owner, owner.name.rsplit('.', 1)[0], {owner.category}, owner.node, False)
        target = self.resolve(extended, extended.name.rsplit('.', 1)[0], {extended.category}, extended.node, False)
        if current is None or target is None:
            return
        visited = set()
        while current is not None and current.key not in visited:
            if current.key == target.key:
                return
            visited.add(current.key)
            if current.extension:
                current = self.resolve(current, current.extension, {current.category, 'abstract'}, current.node, False)
                if current is None:
                    return  # resolve already recorded the future declaration.
            elif not _complete(current.node):
                self.pending('inheritance.implementation_type',
                             f'Complete the type ancestry linking {owner.key} to {target.key}', current.node, '§4.4 N6')
                return
            else:
                self.conflict('inheritance.implementation_type',
                              f'{owner.key} cannot extend {extended.key}: its type does not extend {target.key}', owner.node, '§4.4 N6')
                return
        # An actual AADL extension cycle is diagnosed by inherited(), not by
        # the generic attribute evaluator's independent dependency-cycle guard.

    def check_refinement_prefixes(self, owner):
        """A refinement keyword commits to an inherited target before its body.

        Waiting for the member's terminating semicolon lets greedy decoding
        enter an unrecoverable branch and then emit whitespace indefinitely.
        Only the already determined owner/header and available inherited names
        participate here; an unresolved future ancestor remains Pending.
        """
        members = {'feature': 'features', 'subcomponent': 'children', 'prototype': 'prototypes',
                   'connection': 'connections', 'flow_spec': 'flows'}
        for production, collection in members.items():
            for node in _find(owner.node, production):
                refinement = _first(node, 'refinement')
                name_node = _first(node, 'declaration_name')
                if refinement is None or not refinement.text.strip() or not _complete(name_node):
                    continue
                name = _name(name_node)
                ancestors, unresolved = [], False
                if owner.extension:
                    extended = self.symbols.get(self.qualified(owner.package, owner.extension, owner.node))
                    if extended is None:
                        unresolved = True
                    else:
                        ancestors.append(extended)
                if owner.implementation and collection in {'features', 'prototypes', 'flows'}:
                    base = self.symbols.get(owner.key.rsplit('.', 1)[0])
                    if base is None:
                        unresolved = True
                    else:
                        ancestors.append(base)
                inherited = {}
                for ancestor in ancestors:
                    inherited.update(self.inherited(ancestor, collection))
                    unresolved |= not _complete(ancestor.node)
                if name not in inherited:
                    if unresolved:
                        self.pending('inheritance.refinement_prefix', f'Resolve inherited target {owner.key}.{name}', refinement, '§4.3')
                    else:
                        self.conflict('inheritance.refinement_prefix', f'No inherited {production} {name} can be refined in {owner.key}', refinement, '§4.3')

    def check_arrays(self, owner):
        for dimension in _find(owner.node, 'array_dimension_size'):
            if not _complete(dimension):
                continue
            words = _tokens(dimension.text)
            try:
                value = _number(''.join(words))
                self.require(value > 0 and value == value.to_integral_value(), 'array.positive_dimension',
                             'Array dimensions must be positive integers', dimension, '§4.5')
            except (ValueError, InvalidOperation):
                reference = ''.join(words)
                key = self.property_key(reference, owner.package.name, self.constants)
                if key not in self.constants:
                    self.pending('array.dimension_constant', f'Resolve array dimension {reference}', dimension, '§4.5')
        for selection in _find(owner.node, 'selection_range'):
            if not _complete(selection):
                continue
            parts = _split_top(_tokens(selection.text), '..')
            try:
                values = [_number(''.join(part)) for part in parts]
                self.require(all(n >= 1 and n == n.to_integral_value() for n in values) and
                             (len(values) < 2 or values[0] <= values[1]), 'array.selection_range',
                             'Array selections use ordered positive indices', selection, '§4.5')
            except (ValueError, InvalidOperation):
                self.conflict('array.selection_range', 'Invalid array selection range', selection, '§4.5')

        for child in owner.children.values():
            implementations = _first(child.node, 'array_implementations')
            if implementations is None:
                continue
            choices = _direct(implementations, 'implementation_choice')
            for choice in choices:
                reference = _first(choice, 'classifier_reference')
                if _complete(reference):
                    self.resolve(owner, _name(reference), {child.category}, reference, True)
            sizes = self.dimension_sizes(child.node, owner)
            if sizes and all(size is not None for size in sizes) and _complete(implementations):
                count = 1
                for size in sizes:
                    count *= size
                self.require(len(choices) == count, 'array.implementation_count',
                             f'{child.name} has {count} elements but {len(choices)} implementation choices', implementations, '§4.5')

    def dimension_sizes(self, node, owner):
        sizes = []
        for dimension in _find(node, 'array_dimension'):
            size = _first(dimension, 'array_dimension_size')
            if not _complete(size):
                sizes.append(None)
                continue
            words = _tokens(size.text)
            try:
                sizes.append(int(_number(''.join(words))))
            except (ValueError, InvalidOperation):
                desc = {'scope': owner.package.name, 'node': size, 'kind': 'integer', 'units': None}
                resolved = self.numeric_value(words, desc)
                sizes.append(int(resolved) if resolved is not None else None)
        return sizes

    def check_selected_dimensions(self, member, selections, owner, node):
        sizes = self.dimension_sizes(member.node, owner)
        self.require(bool(sizes) and len(selections) <= len(sizes), 'array.selection_dimensions',
                     f'{member.name} has no matching array dimension for this selection', node, '§4.5')
        for selection, size in zip(selections, sizes):
            if size is None:
                continue  # Open dimensions are resolved by instance consistency.
            try:
                values = [_number(piece) for piece in selection.split('..')]
                self.require(all(1 <= number <= size for number in values), 'array.selection_bounds',
                             f'Array selection exceeds dimension {size} of {member.name}', node, '§4.5')
            except (ValueError, InvalidOperation):
                self.conflict('array.selection_bounds', 'Invalid array selection numeral', node, '§4.5')

    def check_property_duplicates(self, owner):
        # Each association list has its own namespace. Inline lists on two
        # different ports may both set Data_Size; two entries in one list may
        # not. Keeping the containers separate avoids conflating those cases.
        groups = _sections(owner.node, 'properties') + _find(owner.node, 'inline_properties')
        for group in groups:
            self.check_property_association_list(owner, _find(group, 'property_association'))

    def check_property_association_list(self, owner, associations):
        seen = set()
        for prop in associations:
            pn = _first(prop, 'property_name')
            if not _complete(pn):
                continue
            key = self.property_key(_name(pn), owner.package.name, self.property_defs)
            targets = tuple(sorted(_name(n) for n in _find(prop, 'contained_path'))) or ('',)
            bindings = tuple(sorted(_name(n) for n in _find(_first(prop, 'in_binding'), 'classifier_reference'))) if _first(prop, 'in_binding') else ('',)
            for target in targets:
                for binding in bindings:
                    identity = key, target, binding
                    if identity in seen:
                        self.conflict('property.duplicate_association', f'Duplicate property association for {key} on {target or owner.name}', prop, '§11.3 N15')
                    seen.add(identity)
            modal_seen = set()
            for modal in _find(prop, 'modal_value'):
                membership = _first(modal, 'in_modes')
                modes = tuple(sorted(_name(n) for n in _find(membership, 'mode_reference'))) if membership else ('',)
                for target in targets:
                    for mode in modes:
                        identity = target, mode
                        if identity in modal_seen:
                            self.conflict('property.duplicate_modal_value', f'Duplicate modal value for {key} in {mode or "default mode"}', prop, '§11.3')
                        modal_seen.add(identity)

    def require_supported_checks(self, owner):
        """Fail closed for semantic capabilities still awaiting a full rule.

        Recognizing a production is not a proof that every legality rule for
        it has been implemented. These obligations deliberately prevent EOS
        from reporting a successful static validation on an unchecked case.
        """
        checks = {
        }
        for production, (rule, description) in checks.items():
            for node in _find(owner.node, production):
                self.pending('unsupported_static_check.' + rule, description, node, '§4–§12')
        if owner.category == 'feature group':
            self.check_featuregroup_declaration(owner)
        for feature in owner.features.values():
            if feature.kind == 'feature group' and feature.classifier:
                self.featuregroup_signature(owner, feature, feature.node)
        for node in _find(owner.node, 'connection_kind'):
            if _text(node) not in {'port', 'parameter', 'feature', 'feature group', 'access', 'data access', 'bus access', 'virtual bus access', 'subprogram access', 'subprogram group access'}:
                self.pending('unsupported_static_check.connection.kind',
                             f'Complete {_text(node)} connection compatibility', node, '§9')

    def check_modes(self, owner):
        sections = _sections(owner.node, 'modes') + _sections(owner.node, 'type_modes')
        if sections:
            self.require(owner.category not in {'data', 'subprogram group'}, 'modes.owner_category',
                         f'{owner.category} cannot declare modes', owner.node, '§5/§12')
        modes = self.inherited(owner, 'modes')
        names = set(modes)
        initial = [node for node in modes.values() if _first(node, 'initial_marker') is not None]
        required = any(_find(ancestor.node, 'requires_modes') for ancestor in self.namespace_ancestors(owner))
        if owner.implementation:
            base = self.resolve(owner, owner.name.rsplit('.', 1)[0], {owner.category}, owner.node, False)
            required |= bool(base and any(_find(ancestor.node, 'requires_modes') for ancestor in self.namespace_ancestors(base)))
            if base is not None:
                self.require(not (required and sections), 'modes.required_type_implementation',
                             'An implementation of a type with requires modes cannot have a modes subclause', owner.node, '§4.4 L6')
                self.require(not (self.inherited(base, 'modes') and owner.modes), 'modes.type_implementation_declarations',
                             'An implementation cannot declare modes when its type already declares modes', owner.node, '§4.4 L7')
        if len(initial) > 1:
            self.conflict('modes.initial_unique', 'More than one initial mode', owner.node, '§12 L2')
        if initial and required:
            self.conflict('modes.required_initial', 'Required modes do not declare an initial mode', owner.node, '§12 L2')
        if names and not initial and not required and _complete(owner.node) and not owner.extension:
            self.conflict('modes.initial_present', 'Mode declarations require an initial mode', owner.node, '§12 L2')
        transitions = tuple(t for ancestor in self.namespace_ancestors(owner)
                            for t in _find(ancestor.node, 'mode_transition'))
        transition_names = set(self.inherited(owner, 'transitions'))
        deterministic = {}
        for transition in transitions:
            refs = _direct(transition, 'mode_reference')
            for mode_name in refs:
                self.check_mode_reference(owner, mode_name, names)
            for trigger in _find(transition, 'mode_trigger'):
                if _complete(trigger):
                    found = self.path(owner, _name(trigger), trigger)
                    if isinstance(found, _Feature):
                        # A dotted feature-group endpoint still belongs to the
                        # containing component. Only a subcomponent/call denotes
                        # an outgoing endpoint here; dots alone cannot decide it.
                        spelling = _name(trigger)
                        proxy = any(found is f for attribute in ('internal', 'processor')
                                    for f in self.inherited(owner, attribute).values())
                        self.require(found.kind in {'event port', 'event data port'} or proxy and found.kind == 'port', 'modes.trigger_kind',
                                     'Mode triggers require event/event-data ports or internal events', trigger, '§12')
                        head = spelling.split('.')[0]
                        boundary = not proxy and head not in self.inherited(owner, 'children') and head not in self.inherited(owner, 'calls')
                        self.require(found.direction in ({'in', 'in out'} if boundary else {'out', 'in out'}),
                                     'modes.trigger_direction', 'Mode trigger port has incompatible direction', trigger, '§12 N')
                    elif found is not None:
                        self.conflict('modes.trigger_kind', 'A mode trigger must name an event feature, not a component or other declaration', trigger, '§12 N3')
                    if len(refs) == 2 and all(_complete(r) for r in refs):
                        key, destination = (_name(refs[0]), _name(trigger)), _name(refs[1])
                        if key in deterministic and deterministic[key] != destination:
                            self.conflict('modes.deterministic_transition', f'Trigger {key} has two destination modes', transition, '§12 L3')
                        deterministic[key] = destination
        parents = {id(child): parent for parent in _walk(owner.node) for child in getattr(parent, 'children', ())}
        for membership in _find(owner.node, 'in_modes'):
            contexts = [owner]
            container = parents.get(id(membership))
            current = container
            while current is not None and current.production != 'property_association':
                current = parents.get(id(current))
            if current is not None:
                applies = _first(current, 'applies_to')
                mapped = []
                if applies:
                    for target in _find(applies, 'contained_path'):
                        first = _name(target).split('.')[0]
                        child = self.inherited(owner, 'children').get(first)
                        if child and child.classifier:
                            declaration = self.resolve(owner, child.classifier, {child.category}, target)
                            if declaration:
                                mapped.append(declaration)
                if mapped:
                    contexts = mapped
            for context in contexts:
                # The grammar intentionally shares an identifier-list node for
                # in_modes and in_modes_and_transitions. Its *owning production*
                # supplies the inherited reference domain (not the spelling).
                transition_context = container is not None and (
                    container.production in {'connection', 'flow_implementation', 'end_to_end_flow'} or
                    container.production == 'flow_spec' and _first(container, 'refinement') is not None or
                    container.production == 'property_association' and _first(container, 'applies_to') is not None)
                allowed = set(self.inherited(context, 'modes'))
                if transition_context:
                    allowed |= set(self.inherited(context, 'transitions'))
                for reference in _find(membership, 'mode_reference'):
                    self.check_mode_reference(context, reference, allowed)
        for child in self.inherited(owner, 'children').values():
            if not child.classifier:
                continue
            target = self.resolve(owner, child.classifier, {child.category}, child.node)
            if target is None:
                continue
            type_target = self.resolve(target, target.name.rsplit('.', 1)[0], {target.category}, target.node, False) if target.implementation else target
            child_required = bool(type_target and any(_find(ancestor.node, 'requires_modes')
                                                      for ancestor in self.namespace_ancestors(type_target)))
            memberships = _direct(child.node, 'component_in_modes')
            if not memberships:
                if child_required and names:
                    child_names = set(self.inherited(target, 'modes'))
                    self.require(names <= child_names, 'modes.required_implicit_mapping',
                                 f'{child.name} lacks required modes with the containing mode names', child.node, '§12 N5')
                continue
            seen = set()
            for membership in memberships:
                for binding in _direct(membership, 'mode_binding'):
                    refs = _direct(binding, 'mode_reference')
                    if not refs or not _complete(refs[0]):
                        continue
                    parent_mode = _name(refs[0])
                    self.check_mode_reference(owner, refs[0], names | transition_names)
                    if parent_mode in seen:
                        self.conflict('modes.unique_mapping', f'{parent_mode} is mapped more than once', binding, '§12')
                    seen.add(parent_mode)
                    if len(refs) > 1 and not child_required:
                        self.conflict('modes.mapping_requires_modes', 'Explicit mode mapping requires child requires modes', binding, '§12 N5')
                    if child_required:
                        self.check_mode_reference(target, refs[-1], set(self.inherited(target, 'modes')))

    def namespace_ancestors(self, owner):
        """Known inherited scopes, with unresolved ancestors left as obligations.

        Declarations can precede their bases in the same or a later file. A
        complete local declaration therefore does not imply a closed inherited
        namespace. Cycles are diagnosed separately by inherited().
        """
        pending, seen, result = [owner], set(), []
        while pending:
            current = pending.pop()
            if id(current.node) in seen:
                continue
            seen.add(id(current.node))
            result.append(current)
            if current.public_part is not None:
                pending.append(current.public_part)
            references = ([current.extension] if current.extension else [])
            if current.implementation:
                references.append(current.name.split('.', 1)[0])
            for reference in references:
                target = self.symbols.get(self.qualified(current.package, reference, current.node))
                if target is not None:
                    pending.append(target)
        return result

    def namespace_can_grow(self, owner, include_local=True):
        for ancestor in self.namespace_ancestors(owner):
            if (include_local or ancestor is not owner) and not _complete(ancestor.node):
                return True
            references = ([ancestor.extension] if ancestor.extension else [])
            if ancestor.implementation:
                references.append(ancestor.name.split('.', 1)[0])
            if any(self.qualified(ancestor.package, ref, ancestor.node) not in self.symbols for ref in references):
                return True
        return False

    def check_mode_reference(self, owner, node, names):
        if _complete(node) and _name(node) not in names:
            if not self.namespace_can_grow(owner):
                self.conflict('modes.reference', f'Unknown mode {_name(node)} in {owner.key}', node, '§12 N')
            else:
                self.pending('modes.reference', f'Define mode {_name(node)} in {owner.key}', node, '§12 N')

    def check_flows(self, owner):
        sections = _sections(owner.node, 'flow_specs') + _sections(owner.node, 'flow_implementations')
        if not sections:
            return
        self.require(owner.category not in {'data', 'subprogram group', 'memory', 'bus', 'virtual bus'},
                     'flows.owner_category', f'{owner.category} cannot declare flows', owner.node, '§5–§7/§10')
        for spec in _section_items(owner.node, 'flow_specs', 'flow_spec'):
            body = _first(spec, 'flow_spec_body')
            words = _tokens(body.text) if body else []
            flow_kind = words[1] if len(words) > 1 else ''
            for index, endpoint in enumerate(_find(spec, 'flow_endpoint')):
                if _complete(endpoint):
                    found = self.path(owner, _name(endpoint), endpoint)
                    if found is not None:
                        self.require(isinstance(found, _Feature), 'flows.endpoint_feature',
                                     'Flow specification endpoint must be a feature', endpoint, '§10')
                        if isinstance(found, _Feature):
                            direction = 'in' if flow_kind == 'sink' or flow_kind == 'path' and index == 0 else 'out'
                            compatible = self.flow_feature_compatible(owner, found, direction, endpoint)
                            if compatible is not None:
                                self.require(compatible, 'flows.endpoint_direction',
                                             f'{flow_kind} flow endpoint {_name(endpoint)} cannot carry a flow {direction}', endpoint, '§10.1 L2–L12')
        base = self.resolve(owner, owner.name.rsplit('.', 1)[0], {owner.category}, owner.node, False) if owner.implementation else owner
        specifications = self.inherited(base, 'flows') if base else {}
        for production in ('flow_implementation', 'end_to_end_flow'):
            for flow in _section_items(owner.node, 'flow_implementations', production):
                chain = _first(flow, 'flow_chain')
                if chain is None:
                    continue
                segments = _direct(chain, 'flow_segment')
                if not all(_complete(segment) for segment in segments):
                    continue
                expanded = [self.flow_segment(owner, _name(segment), segment) for segment in segments]
                if any(part is None for part in expanded):
                    continue
                for i, part in enumerate(expanded):
                    if part[2] == 'bidirectional_connection' and i and expanded[i - 1][1] == part[1]:
                        expanded[i] = part[1], part[0], part[2]
                # Each flow segment supplies its boundary interface; a local
                # connection supplies both linked endpoints. Equality therefore
                # checks actual continuity, not merely existence of each name.
                for left, right in zip(expanded, expanded[1:]):
                    # 10.2 L6 explicitly permits a direct input-to-output
                    # implementation with no intervening connections.
                    connected = 'connection' in left[2] or 'connection' in right[2]
                    a, b = left[1], right[0]
                    matches = a == b or bool(a and b and (
                        a.endswith('.*') and b.startswith(a[:-1]) or b.endswith('.*') and a.startswith(b[:-1])))
                    if connected and not matches:
                        self.conflict('flows.chain_continuity', f'Flow chain jumps from {left[1]} to {right[0]}', flow, '§10.2')
                if production == 'end_to_end_flow':
                    if _complete(flow) and expanded:
                        self.require(expanded[0][2] in {'source', 'path', 'component'} and expanded[-1][2] in {'sink', 'path', 'component'},
                                     'flows.end_to_end_bounds', 'End-to-end flow has incompatible source/sink component flows', flow, '§10.3')
                else:
                    name = _field(flow, 'flow_reference')
                    spec = specifications.get(name)
                    if spec is None:
                        if base and _complete(base.node):
                            self.conflict('flows.specification_defined', f'No flow specification {name}', flow, '§10.2')
                        else:
                            self.pending('flows.specification_defined', f'Define flow specification {name}', flow, '§10.2')
                    elif expanded and _complete(flow):
                        expected = self.flow_spec_bounds(spec, '')
                        kind = _text(_first(flow, 'flow_kind'))
                        if expected:
                            self.require(kind == expected[2], 'flows.implementation_kind', 'Flow implementation kind differs from specification', flow, '§10.2')
                            if expected[0] is not None:
                                self.require(expanded[0][0] == expected[0], 'flows.entry', 'Flow entry differs from its specification', flow, '§10.2')
                            if expected[1] is not None:
                                self.require(expanded[-1][1] == expected[1], 'flows.exit', 'Flow exit differs from its specification', flow, '§10.2')
                self.check_flow_modes(owner, flow, segments)
        if _complete(owner.node):
            all_modes = set(self.inherited(owner, 'modes'))
            groups = {}
            for flow in _section_items(owner.node, 'flow_implementations', 'flow_implementation'):
                groups.setdefault(_field(flow, 'flow_reference'), []).append(flow)
            for name, flows in groups.items():
                spec = specifications.get(name)
                if spec:
                    wanted = self.membership(spec, all_modes)
                    actual = set().union(*(self.membership(flow, all_modes) for flow in flows))
                    self.require(wanted <= actual, 'flows.mode_coverage',
                                 f'Flow implementations do not cover all modes of {name}', owner.node, '§10.2 L4')

    def flow_feature_compatible(self, owner, feature, direction, node, active=()):
        """Flow direction is an attribute of the endpoint's feature kind.

        Access features use Access_Right, not requires/provides. A feature-group
        flow needs one suitable member (or an empty group), rather than every
        member having a common direction (§10.1 L4–L10).
        """
        if feature.kind in PORT_KINDS or feature.kind == 'parameter':
            return feature.direction in {direction, 'in out'}
        if feature.kind == 'data access':
            capabilities = self.access_capabilities(owner, feature, node)
            return None if capabilities is None else ('read' if direction == 'in' else 'write') in capabilities
        if feature.kind == 'subprogram access':
            # The standard permits access features as flow endpoints; the
            # requires/provides marker is not a port direction (§10.1 N2).
            return True
        if feature.kind != 'feature group':
            return False
        if not feature.classifier:
            return True
        target = self.resolve(owner, feature.classifier, {'feature group'}, node, False)
        if target is None:
            return None
        if target.key in active:
            self.conflict('feature_group.recursive_signature', 'Recursive feature-group flow interface', node, '§8.2')
            return False
        inverse = _first(target.node, 'inverse_clause')
        flip = feature.inverse
        if inverse is not None:
            reference = _first(inverse, 'classifier_reference')
            if not _complete(reference):
                return None
            original = self.resolve(target, _name(reference), {'feature group'}, reference, False)
            if original is None:
                return None
            # An explicit inverse keeps its declared, already reversed members;
            # an implicit inverse obtains members from its referenced type.
            if not self.inherited(target, 'features'):
                target, flip = original, not flip
        members = tuple(self.inherited(target, 'features').values())
        if not members:
            return True
        effective_direction = ('out' if direction == 'in' else 'in') if flip else direction
        results = [self.flow_feature_compatible(target, member, effective_direction, node, active + (target.key,))
                   for member in members]
        if any(value is True for value in results):
            return True
        if any(value is None for value in results) or self.namespace_can_grow(target):
            self.pending('flows.group_interface', f'Complete the compatible flow interface of {target.key}', node, '§10.1 L6–L10')
            return None
        return False

    def membership(self, node, all_modes):
        membership = _first(node, 'in_modes', 'component_in_modes')
        if membership is None:
            return set(all_modes)
        if membership.production == 'component_in_modes':
            return {_name(_first(binding, 'mode_reference')) for binding in _direct(membership, 'mode_binding')}
        return {_name(n) for n in _direct(membership, 'mode_reference') if _complete(n)}

    def check_flow_modes(self, owner, flow, segments):
        all_modes = set(self.inherited(owner, 'modes'))
        modes = self.membership(flow, all_modes)
        if not modes:
            return
        children = self.inherited(owner, 'children')
        for segment in segments:
            name = _name(segment)
            referenced = owner.connections.get(name)
            if referenced is None and name.split('.')[0] in children:
                referenced = children[name.split('.')[0]].node
            if referenced is not None:
                active = self.membership(referenced, all_modes)
                self.require(modes <= active, 'flows.member_mode_coverage',
                             f'{name} is inactive in a mode where its flow is declared', segment, '§10.2 L5/§10.3 L5')

    def flow_spec_bounds(self, spec, prefix):
        body = _first(spec, 'flow_spec_body')
        if body is None:
            return None
        words = _tokens(body.text)
        kind = words[1] if len(words) > 1 else ''
        ends = [prefix + _name(n) for n in _find(body, 'flow_endpoint')]
        if kind == 'source' and ends:
            return None, ends[0], kind
        if kind == 'sink' and ends:
            return ends[0], None, kind
        if kind == 'path' and len(ends) == 2:
            return ends[0], ends[1], kind
        return None

    def flow_segment(self, owner, value, node):
        if value in owner.connections:
            body = _first(owner.connections[value], 'connection_body')
            if body:
                kind = 'bidirectional_connection' if _text(_first(body, 'connection_arrow')) == '<->' else 'connection'
                return _field(body, 'source_endpoint'), _field(body, 'destination_endpoint'), kind
            self.pending('flows.refined_connection', f'Resolve refined connection {value}', node, '§10.2')
            return None
        parts = value.split('.')
        if len(parts) == 1:
            feature = self.inherited(owner, 'features').get(value)
            if feature:
                return value, value, 'feature'
            child = self.inherited(owner, 'children').get(value)
            if child:
                return value + '.*', value + '.*', 'component'
        if len(parts) >= 2:
            child = self.inherited(owner, 'children').get(parts[0])
            if child and child.classifier:
                target = self.resolve(owner, child.classifier, {child.category}, node)
                if target:
                    if target.implementation:
                        target = self.resolve(target, target.name.rsplit('.', 1)[0], {target.category}, node, False)
                    if target:
                        spec = self.inherited(target, 'flows').get(parts[-1])
                        if spec:
                            return self.flow_spec_bounds(spec, '.'.join(parts[:-1]) + '.')
                        if _complete(target.node):
                            self.conflict('flows.segment_defined', f'No flow specification {value}', node, '§10.2')
                        else:
                            self.pending('flows.segment_defined', f'Define flow specification {value}', node, '§10.2')
                        return None
                return None
        self.conflict('flows.segment_kind', f'{value} is not a flow-chain feature, connection or subcomponent flow', node, '§10.2')
        return None

    def check_prototypes(self, owner):
        for prototype in owner.prototypes.values():
            for reference in _find(prototype, 'classifier_reference'):
                category_node = _first(prototype, 'category', recursive=True)
                categories = {_text(category_node)} if category_node else None
                if _complete(reference):
                    self.resolve(owner, _name(reference), categories, reference)
        def visit(parent):
            for bindings in _direct(parent, 'prototype_bindings'):
                target = owner
                reference = _first(parent, 'classifier_reference')
                if reference is not None:
                    if not _complete(reference):
                        continue
                    target = self.resolve(owner, _name(reference), None, reference)
                if target is None:
                    continue
                formals = self.inherited(target, 'prototypes')
                seen = set()
                for binding in _direct(bindings, 'prototype_binding'):
                    name_node = _first(binding, 'prototype_name')
                    if not _complete(name_node):
                        continue
                    name = _name(name_node)
                    if name in seen:
                        self.conflict('prototype.unique_binding', f'Prototype {name} is bound twice', binding, '§4.7')
                    seen.add(name)
                    formal = formals.get(name)
                    if formal is None:
                        # A closed target can still inherit its formals from a
                        # future base; use the inherited namespace's closure,
                        # not just this declaration's trailing semicolon.
                        if _complete(target.node) and not self.namespace_can_grow(target):
                            self.conflict('prototype.formal_defined', f'{target.key} has no prototype {name}', binding, '§4.7')
                        else:
                            self.pending('prototype.formal_defined', f'Define prototype {target.key}.{name}', binding, '§4.7')
                        continue
                    actual = _first(binding, 'prototype_actual')
                    if actual is not None:
                        # The formal's constraint is written in its declaring
                        # classifier, while actual names are written at this
                        # binding site. Inherited formals must retain that
                        # lexical owner instead of borrowing the caller's package.
                        formal_owner = next((scope for scope in self.namespace_ancestors(target)
                                             if any(item is formal for item in scope.prototypes.values())), None)
                        if formal_owner is None:
                            raise RuleDefinitionError('An inherited prototype has no declaring scope')
                        # An extension's inherited formal lives in the derived
                        # classifier for effective-property lookup, even though
                        # its classifier constraint keeps the original lexical
                        # owner. Subcomponent actuals instead use their target.
                        policy_owner = owner if parent is owner.node or parent.production in {'type_extension', 'implementation_extension'} else target
                        self.check_prototype_actual(owner, formal, actual, formal_owner, policy_owner)
            for child in getattr(parent, 'children', ()):
                visit(child)
        visit(owner.node)

    def check_prototype_actual(self, owner, formal, actual, formal_owner, policy_owner):
        formal_spec = _first(formal, 'component_prototype', 'feature_group_prototype', 'feature_prototype', recursive=True)
        if formal_spec is None:
            return
        if formal_spec.production == 'component_prototype':
            category = _text(_first(formal_spec, 'category'))
            values = _find(actual, 'component_actual')
            is_array = _first(formal_spec, 'prototype_array') is not None
            has_list = _first(actual, 'component_actual_list') is not None
            if _complete(actual):
                self.require(bool(values) and is_array == has_list, 'prototype.actual_shape',
                             'Component prototype binding has incompatible scalar/array shape', actual, '§4.7')
            for value in values:
                actual_category = _first(value, 'category')
                if _complete(actual_category):
                    self.require(category == 'abstract' or _text(actual_category) == category, 'prototype.actual_category',
                                 f'Prototype requires {category}, found {_text(actual_category)}', value, '§4.7')
                reference = _first(value, 'classifier_reference')
                if _complete(reference):
                    target = self.resolve(owner, _name(reference), None if category == 'abstract' else {category}, reference)
                    self.check_prototype_constraint(formal_owner, formal_spec, target, reference, formal, policy_owner, owner)
        elif formal_spec.production == 'feature_group_prototype':
            value = _first(actual, 'feature_group_actual')
            if value is None:
                if _complete(actual):
                    self.conflict('prototype.actual_kind', 'Feature-group prototype requires a feature-group actual', actual, '§4.7')
            else:
                reference = _first(value, 'classifier_reference')
                if _complete(reference):
                    target = self.resolve(owner, _name(reference), {'feature group'}, reference)
                    self.check_prototype_constraint(formal_owner, formal_spec, target, reference, formal, policy_owner, owner)
        else:
            value = _first(actual, 'port_spec', 'access_spec', 'feature_prototype')
            if value is None:
                if _complete(actual):
                    self.conflict('prototype.actual_kind', 'Feature prototype requires a feature actual', actual, '§4.7')
                return
            formal_direction = _text(_first(formal_spec, 'feature_direction'))
            actual_direction = _text(_first(value, 'direction', 'feature_direction', 'access_direction'))
            if formal_direction and actual_direction:
                self.require(formal_direction == actual_direction, 'prototype.feature_direction',
                             'Feature prototype actual has incompatible direction', actual, '§4.7')
            reference = _first(value, 'classifier_reference', recursive=True)
            if _complete(reference):
                target = self.resolve(owner, _name(reference), None, reference)
                self.check_prototype_constraint(formal_owner, formal_spec, target, reference, formal, policy_owner, owner)

    def prototype_substitution_rule(self, formal_owner, prototype, policy_owner, binding_owner):
        """Select a known substitution policy without inventing a default.

        The predeclared property's actual definition controls both its default
        and whether owner values are inherited. Unsupported evaluations remain
        obligations, rather than silently using Type_Extension (§4.7, §11.3).
        """
        key = 'modeling_properties::prototype_substitution_rule'
        definition = self.property_defs.get(key)
        if definition is None:
            self.pending('prototype.substitution_policy', 'Load the Prototype_Substitution_Rule definition', prototype, '§4.7')
            return None

        def selected(properties, scope, allowed_targets=()):
            matches = []
            for prop in properties:
                if self.property_key(_field(prop, 'property_name'), scope.package.name, self.property_defs) != key:
                    continue
                applies = _first(prop, 'applies_to')
                if applies is not None:
                    targets = {_name(path) for path in _find(applies, 'contained_path')}
                    if not targets & set(allowed_targets):
                        continue
                matches.append(prop)
            if not matches:
                return False, None
            prop = matches[0]
            assignment = _first(prop, 'assignment')
            values = _find(assignment, 'modal_value')
            if (len(matches) != 1 or not _complete(prop) or len(values) != 1
                    or _find(prop, 'in_modes') or _first(prop, 'in_binding') is not None
                    or _field(prop, 'assignment_operator') != '=>'):
                self.pending('prototype.substitution_policy', 'Evaluate modal, binding-specific or incomplete prototype policy', prop, '§4.7/§11.3')
                return True, None
            value = _text(_first(values[0], 'property_value'))
            if value not in {'classifier_match', 'type_extension', 'signature_match'}:
                self.pending('prototype.substitution_policy', 'Evaluate computed or referenced prototype policy', prop, '§4.7/§11.3')
                return True, None
            return True, value

        present, value = selected(_section_items(prototype, 'inline_properties', 'property_association'), formal_owner)
        if present:
            return value
        if not _complete(prototype):
            self.pending('prototype.substitution_policy', 'Complete the formal prototype policy', prototype, '§4.7')
            return None
        if _first(definition['node'], 'inherit_marker') is not None:
            active = set()

            def inherited_policy(scope):
                if id(scope.node) in active:
                    return False, None  # declaration inheritance reports its own cycle
                active.add(id(scope.node))
                present, value = selected(_section_items(scope.node, 'properties', 'property_association'),
                                          scope, (_field(prototype, 'declaration_name'),))
                if present:
                    return True, value
                if not _complete(scope.node):
                    self.pending('prototype.substitution_policy', 'An open classifier can still declare its prototype policy', scope.node, '§4.7/§11.3')
                    return True, None
                ancestors = []
                if scope.public_part is not None:
                    ancestors.append(scope.public_part)
                if scope.extension:
                    ancestor = self.resolve(scope, scope.extension, None, scope.node)
                    if ancestor is None:
                        return True, None
                    ancestors.append(ancestor)
                if scope.implementation:
                    ancestor = self.resolve(scope, scope.key.rsplit('.', 1)[0], {scope.category}, scope.node, False)
                    if ancestor is None:
                        return True, None
                    ancestors.append(ancestor)
                for ancestor in ancestors:
                    present, value = inherited_policy(ancestor)
                    if present:
                        return True, value
                return False, None

            present, value = inherited_policy(policy_owner)
            if present:
                return value
            if binding_owner is not policy_owner:
                # Effective properties inherited through an instantiated
                # subcomponent's containing component need an instance context.
                # Do not silently turn a known contextual override into the
                # classifier-definition default during declaration checking.
                for scope in self.namespace_ancestors(binding_owner):
                    present, _ = selected(_section_items(scope.node, 'properties', 'property_association'), scope)
                    if present or not _complete(scope.node):
                        self.pending('prototype.substitution_context',
                                     'Resolve the containing-component prototype policy context', scope.node, '§4.7/§11.3')
                        return None
        default = definition.get('default')
        if default is None or len(default) != 1 or default[0] not in {'classifier_match', 'type_extension', 'signature_match'}:
            self.pending('prototype.substitution_policy', 'Evaluate the declared prototype policy default', definition['node'], '§4.7/§11.3')
            return None
        return default[0]

    def check_prototype_constraint(self, formal_owner, formal, actual, node, prototype, policy_owner, binding_owner):
        reference = _first(formal, 'classifier_reference')
        if actual is None or not _complete(reference):
            return
        constraint = self.resolve(formal_owner, _name(reference), None, reference)
        if constraint is None:
            return
        policy = self.prototype_substitution_rule(formal_owner, prototype, policy_owner, binding_owner)
        if policy is None:
            return
        if policy == 'signature_match':
            self.pending('prototype.signature_match_unsupported',
                         'Signature_Match requires a full classifier-signature compatibility check', node, '§4.5 L5 / §4.7')
            return
        # Classifier_Match means the same component type (§4.5 L4), not
        # identical implementation names. Both implemented policies compare
        # types even when either classifier reference selects an implementation.
        actual_type = (self.resolve(actual, actual.key.rsplit('.', 1)[0], {actual.category}, node, False)
                       if actual.implementation else actual)
        constraint_type = (self.resolve(constraint, constraint.key.rsplit('.', 1)[0], {constraint.category}, reference, False)
                           if constraint.implementation else constraint)
        if actual_type is None or constraint_type is None:
            return
        matches = (actual_type.key == constraint_type.key if policy == 'classifier_match'
                   else self.is_subtype(actual_type, constraint_type))
        if not matches:
            # A known actual may still extend a forward-declared classifier.
            # That later ancestor can establish the required subtype relation;
            # failure to find it in this snapshot is not a final incompatibility.
            report = self.pending if policy == 'type_extension' and self.namespace_can_grow(actual_type) else self.conflict
            report('prototype.classifier_constraint',
                   f'{actual.key} must satisfy {policy} against prototype constraint {constraint.key}', node, '§4.7')

    def check_package_aliases(self, package):
        """An unused alias still has naming/type obligations (§4.2 N11–N17)."""
        claimed = {}
        for part in package.parts:
            for section in part.children:
                if section.production not in {'public_section', 'private_section'}:
                    continue
                scope = self.attributes.get(section, 'scope')
                for alias in (n for n in _walk(section) if n.production in
                              {'package_alias', 'classifier_alias', 'wildcard_alias'}):
                    target_node = self.bound(alias, 'target')
                    if not _complete(target_node):
                        continue
                    target_name = _name(target_node)
                    target_package = target_name.rsplit('::', 1)[0] if alias.production == 'classifier_alias' else target_name
                    if target_package not in scope['imports']:
                        report = self.pending if (section.production == 'private_section' and
                                                 self.public_visibility_open(package)) else self.conflict
                        report('alias.import_required', f'Alias target package {target_package} must be explicitly imported', alias, '§4.2 N11')
                    target = self.packages.get(target_package)
                    if target is None:
                        if target_package in self.property_sets:
                            self.conflict('alias.package_kind', f'{target_package} is a property set, not a package', alias, '§4.2 N11')
                        else:
                            self.pending('alias.package_defined', f'Define alias target package {target_package}', alias, '§4.2 N11')
                    if alias.production == 'classifier_alias':
                        classifier = self.symbols.get(target_name)
                        if classifier is None:
                            public = target.sections.get('public_section') if target is not None else None
                            report = self.conflict if _complete(public) else self.pending
                            report('alias.classifier_defined', f'Define aliased type {target_name}', alias, '§4.2 N12')
                        else:
                            kind = _first(alias, 'category', 'feature_group_category')
                            if _complete(kind):
                                self.require(classifier.category == _text(kind), 'alias.category',
                                             f'Alias category {_text(kind)} differs from {classifier.category}', alias, '§4.2 N12/N13')
                            self.require(not classifier.private, 'alias.public_target', 'An alias cannot expose a private classifier', alias, '§4.2 N12')
                            self.require(not classifier.implementation, 'alias.type_target', 'An alias must name a component or feature-group type', alias, '§4.2 N13')
                    if alias.production == 'wildcard_alias':
                        aliases = [declaration.name for declaration in self.symbols.values()
                                   if declaration.package.name == target_package and not declaration.private and not declaration.implementation]
                    else:
                        name = self.bound(alias, 'name')
                        aliases = [_name(name) if name is not None else target_name.rsplit('::', 1)[-1]]
                    for name in aliases:
                        self.require(name not in claimed, 'alias.unique_name', f'Duplicate imported alias name {name}', alias, '§4.2 N14–N16')
                        self.require(package.name + '::' + name not in self.symbols, 'alias.classifier_collision',
                                     f'Alias {name} collides with a classifier declaration', alias, '§4.2 N14–N16')
                        if alias.production == 'package_alias':
                            self.require(name not in scope['imports'] and name != package.name,
                                         'alias.package_name_collision', f'Alias {name} conflicts with a package name', alias, '§4.2 N14')
                        claimed[name] = alias

    def global_checks(self):
        for package in self.packages.values():
            self.check_package_aliases(package)
            for imported in package.imports | package.import_all:
                if imported not in self.packages and imported not in self.property_sets:
                    self.pending('import.defined', f'Provide imported model unit {imported}', package.node, '§4.2')
        graph = {}
        for key, public in self.symbols.items():
            owner = self._private_completions.get(key, public)
            if owner.implementation:
                graph[owner.key] = [self.qualified(owner.package, child.classifier, owner.node) for child in
                                    self.inherited(owner, 'children').values() if '.' in child.classifier]
        active, done = set(), set()
        def visit(key):
            if key in active:
                self.conflict('containment.acyclic', f'Recursive component containment through {key}',
                              self.symbols[key].node if key in self.symbols else None, '§4.3/§13')
                return
            if key in done:
                return
            active.add(key)
            for target in graph.get(key, ()):
                visit(target)
            active.remove(key)
            done.add(key)
        for key in graph:
            visit(key)
        # Intersect repeated uses of a future port and propagate kind relations.
        # This proves conflicts, not existential completion of arbitrary prefixes.
        changed = True
        while changed:
            changed = False
            for left, right, bidirectional, node in self.edges:
                a, b = self.interfaces[left]['kinds'], self.interfaces[right]['kinds']
                pairs = {(x, y) for x in a for y in b if y in PORT_DESTINATIONS[x] and (not bidirectional or x == y)}
                if not pairs:
                    self.conflict('connection.kind_obligations', f'Incompatible pending interfaces {left} and {right}', node, '§9')
                    continue
                aa, bb = {x for x, _ in pairs}, {y for _, y in pairs}
                changed |= aa != a or bb != b
                self.interfaces[left]['kinds'], self.interfaces[right]['kinds'] = aa, bb
        if self.plan is not None:
            for name in self.plan:
                matches = [d for d in self.symbols.values() if name in {d.key, d.name}]
                if not matches or not any(_complete(d.node) for d in matches):
                    self.pending('plan.fulfilled', f'Fulfill planned declaration {name}')

    def run(self):
        self.collect()
        self.check_property_declarations()
        for owner in tuple(self.symbols.values()) + tuple(self._private_completions.values()):
            self.check_declaration(owner)
        self.global_checks()
        if not self.results:
            self.result(Status.SATISFIED, 'project.static_constraints', 'All implemented static constraints are satisfied')
        attributes = {'declarations': tuple(self.symbols), 'property_definitions': tuple(self.property_defs),
                      'pending_references': tuple(k for k in self.demands if k not in self.symbols)}
        return SemanticReport(results=tuple(self.results), attributes=attributes)

    def check_property_declarations(self):
        builtins = {name[:-5].lower() for name in BUILTIN_SOURCES}
        def inspect(desc, active=()):
            if not desc:
                return
            if desc['kind'] == 'named':
                key = self.property_type_key(desc)
                if key is None:
                    return
                self.check_property_reference_visibility(desc['reference'], desc['scope'], desc['node'])
                if key in active:
                    self.conflict('property.type_acyclic', f'Property type cycle through {key}', desc['node'], '§11')
                    return
                target = self.property_types.get(key)
                if target is None:
                    self.pending('property.type_defined', f'Define property type {key}', desc['node'], '§11')
                else:
                    inspect(target, active + (key,))
                return
            if 'item' in desc:
                inspect(desc['item'], active)
                if desc['kind'] == 'range':
                    base = self.resolve_type(desc['item'])
                    if base is not None:
                        self.require(base['kind'] in {'integer', 'real'}, 'property.range_base_type',
                                     'A range type must have an integer or real base', desc['node'], '§11.1.1')
            if desc['kind'] in {'integer', 'real'} and desc.get('units'):
                inspect(desc['units'], active)
                units = self.resolve_type(desc['units'])
                if units is not None:
                    self.require(units['kind'] == 'units', 'property.units_type',
                                 'A numeric units designator must name a units type', desc['node'], '§11.1.1')
            if desc['kind'] in {'integer', 'real'} and desc.get('bounds'):
                # Check the type declaration even if no property ever uses it.
                # Suppress its own range restriction while checking its bounds,
                # otherwise each endpoint would recursively check itself.
                scalar = dict(desc, bounds=())
                for bound in desc['bounds']:
                    self.validate_value(bound, scalar, desc['node'])
                if len(desc['bounds']) == 2:
                    low, high = (self.numeric_value(bound, scalar) for bound in desc['bounds'])
                    if low is not None and high is not None:
                        self.require(low <= high, 'property.type_range_order',
                                     'Numeric type lower bound exceeds its upper bound', desc['node'], '§11.1.1 L1')
            for child in desc.get('fields', {}).values():
                inspect(child, active)
        for key, desc in self.property_types.items():
            if key.rsplit('::', 1)[0] not in builtins:
                inspect(desc, (key,))
        for key, definition in self.property_defs.items():
            if key.rsplit('::', 1)[0] not in builtins:
                inspect(definition['type'])
                self.require('all' not in definition['owners'] or len(definition['owners']) == 1,
                             'property.all_exclusive', 'The all property owner cannot be combined with other owners',
                             definition['node'], '§11.1.2 L3')
                for owner in definition['owners']:
                    if '::' in owner:
                        # An applies-to classifier uses the property's defining
                        # set's imports, not the package of a later association.
                        # A qualified spelling alone does not grant visibility.
                        self.check_property_reference_visibility(owner, definition['scope'], definition['node'])
                        classifier = self.symbols.get(owner)
                        if classifier is None:
                            self.pending('property.owner_defined', f'Define property owner classifier {owner}', definition['node'], '§11.1.2 N4')
                        elif classifier.private:
                            self.conflict('property.owner_public', f'Property owner classifier {owner} must be public', definition['node'], '§11.1.2 N4')
                if definition['default'] is not None:
                    default_type = self.resolve_type(definition['type'])
                    while default_type is not None and default_type['kind'] == 'list':
                        default_type = self.resolve_type(default_type['item'])
                    if default_type is not None:
                        self.require(default_type['kind'] not in {'classifier', 'reference'},
                                     'property.reference_default', 'Classifier/reference properties cannot declare a default value',
                                     definition['node'], '§11.1.2 L2')
                    self.validate_value(definition['default'], definition['type'], definition['node'])
        for key, constant in self.constants.items():
            if key.rsplit('::', 1)[0] not in builtins:
                inspect(constant['type'])
                # Mirror the fixed OSATE validator's recursive type walk:
                # checkPropertyConstant / containsClassifierOrReferenceType
                # at b8308b7, and AS5506D §11.1.3 L1. Empty lists must not
                # bypass the ban merely because there is no element to visit.
                def forbidden_type(desc, visited=()):
                    desc = self.resolve_type(desc)
                    if desc is None or id(desc) in visited:
                        return False
                    if desc['kind'] in {'classifier', 'reference'}:
                        return True
                    nested = list(desc.get('fields', {}).values())
                    if 'item' in desc:
                        nested.append(desc['item'])
                    return any(forbidden_type(item, visited + (id(desc),)) for item in nested)
                self.require(not forbidden_type(constant['type']), 'property.constant_reference_type',
                             'Property constants cannot contain classifier/reference types', constant['node'], '§11.1.3 L1')
                self._constant_stack.add(key)
                try:
                    self.validate_value(constant['value'], constant['type'], constant['node'])
                finally:
                    self._constant_stack.remove(key)


def _top_index(words, sought):
    depth = 0
    for i, token in enumerate(words):
        if depth == 0 and token == sought:
            return i
        depth += token in {'(', '[', '{'}
        depth -= token in {')', ']', '}'}
    return None


def _split_top(words, separator):
    result, start, depth = [], 0, 0
    for i, token in enumerate(words):
        if depth == 0 and token == separator:
            result.append(words[start:i])
            start = i + 1
        depth += token in {'(', '[', '{'}
        depth -= token in {')', ']', '}'}
    result.append(words[start:])
    return result


def _owner_labels(owner):
    if isinstance(owner, _Declaration):
        return {owner.category, 'component', 'classifier',
                'component implementation' if owner.implementation else 'component type'}
    if isinstance(owner, _Feature):
        labels = {owner.kind, 'feature'}
        if owner.kind in PORT_KINDS:
            labels.add('port')
        return labels
    if isinstance(owner, _Child):
        return {owner.category, 'component', 'subcomponent'}
    return {getattr(owner, 'production', '').replace('_', ' ')}


# These declarations are checked by the loader against real source productions.
# Adding a grammar construct therefore cannot silently bypass semantic review.
BINDINGS = {
    'internal_feature': {'name': 'declaration_name', 'classifier': 'classifier_reference'},
    'processor_feature': {'name': 'declaration_name', 'classifier': 'classifier_reference'},
    'package_spec': {'name': 'package_name', 'public': 'public_section', 'private': 'private_section', 'end': 'end_package_name'},
    'import_declaration': {'names': 'import_name'},
    'package_alias': {'name': 'declaration_name', 'target': 'package_name'},
    'classifier_alias': {'name': 'declaration_name', 'target': 'classifier_reference'},
    'wildcard_alias': {'target': 'package_name'},
    'component_type': {'name': 'declaration_name', 'category': 'category', 'extension': 'type_extension', 'body': 'type_body', 'end': 'component_end'},
    'component_implementation': {'name': 'implementation_name', 'category': 'category', 'extension': 'implementation_extension', 'body': 'implementation_body', 'end': 'implementation_end'},
    'feature_group_type': {'name': 'declaration_name', 'extension': 'type_extension', 'features': 'features', 'inverse': 'inverse_clause', 'end': 'component_end'},
    'component_end': {'name': 'end_name'},
    'implementation_end': {'name': 'end_implementation_name'},
    'type_extension': {'target': 'classifier_reference'},
    'implementation_extension': {'target': 'classifier_reference'},
    'feature': {'name': 'declaration_name', 'spec': 'feature_spec', 'refinement': 'refinement', 'properties': 'inline_properties'},
    'subcomponent': {'name': 'declaration_name', 'category': 'category', 'classifier': 'classifier_reference', 'refinement': 'refinement'},
    'connection': {'name': 'declaration_name', 'body': 'connection_body', 'refinement': 'refinement'},
    'connection_body': {'kind': 'connection_kind', 'source': 'source_endpoint', 'destination': 'destination_endpoint', 'arrow': 'connection_arrow'},
    'property_association': {'name': 'property_name', 'assignment': 'assignment', 'targets': 'applies_to', 'binding': 'in_binding'},
    'property_set': {'name': 'declaration_name', 'types': 'property_type_declaration', 'properties': 'property_definition', 'constants': 'property_constant', 'end': 'end_name'},
    'property_type_declaration': {'name': 'declaration_name', 'type': 'property_type'},
    'property_definition': {'name': 'declaration_name', 'type': 'property_type_designator', 'owners': 'property_owner'},
    'property_constant': {'name': 'declaration_name', 'type': 'property_type_designator', 'value': 'property_value'},
    'property_value': {'expression': 'property_expression', 'list': 'list_value'},
    'unit_conversion': {'name': 'declaration_name', 'base': 'unit_name', 'factor': 'numeric_value'},
    'record_field': {'name': 'declaration_name', 'type': 'property_type_designator'},
    'array_dimension': {'size': 'array_dimension_size'},
    'array_selection': {'range': 'selection_range'},
    'prototype': {'name': 'declaration_name', 'spec': 'prototype_spec'},
    'prototype_binding': {'formal': 'prototype_name', 'actual': 'prototype_actual'},
    'subprogram_call': {'name': 'declaration_name', 'target': 'called_subprogram'},
    'mode': {'name': 'declaration_name', 'initial': 'initial_marker'},
    'mode_transition': {'name': 'declaration_name', 'modes': 'mode_reference', 'triggers': 'mode_trigger'},
    'in_modes': {'modes': 'mode_reference'},
    'component_in_modes': {'mappings': 'mode_binding'},
    'flow_spec': {'name': 'declaration_name', 'body': 'flow_spec_body', 'refinement': 'refinement'},
    'flow_implementation': {'name': 'flow_reference', 'kind': 'flow_kind', 'chain': 'flow_chain'},
    'end_to_end_flow': {'name': 'declaration_name', 'chain': 'flow_chain'},
    'annex_library': {'name': 'annex_name', 'body': 'annex_body'},
    'annex_subclause': {'name': 'annex_name', 'body': 'annex_body'},
}

# These wrappers and scalar leaves have no independent attribute environment.
# Their facts are consumed by their explicitly bound parent productions above.
# This is intentionally a literal inventory: a new source production must be
# reviewed and added here or bound, rather than being automatically ignored.
STRUCTURAL = frozenset('''
opaque_annex_body empty_annex_body adjacent_constant_marker adjacent_signed_range
adjacent_signed_expression adjacent_bracket_expression adjacent_word_value adjacent_delimited_value
adjacent_modal_value adjacent_word_modal_value adjacent_delimited_modal_value adjacent_word_assignment adjacent_delimited_assignment
end_to_end_chain end_to_end_refinement_chain
word_numeric_value signed_numeric_value word_numeric_term signed_numeric_term word_type_range signed_type_range
word_path_segment delimited_path_segment word_contained_path delimited_contained_path word_applies_to delimited_applies_to
word_value delimited_value word_expression delimited_expression word_modal_value delimited_modal_value word_assignment delimited_assignment word_type_designator delimited_type_designator word_list_type delimited_list_type word_property_type delimited_property_type word_number_type delimited_number_type word_range_type delimited_range_type word_classifier_type delimited_classifier_type word_reference_type delimited_reference_type
aadl_declaration aadl_specification abstract_feature_spec access_category access_direction access_spec
alias_declaration annex_body annex_metaclass annex_name applies_to array_dimension_size array_dimensions
array_implementations assignment assignment_operator boolean_literal call_sequence called_subprogram calls
category classifier_reference classifier_term classifier_type component_actual component_actual_list
component_prototype computed_term connection_arrow connection_kind connections constant_marker contained_path
declaration_name destination_endpoint direction end_implementation_name end_name end_package_name endpoint
endpoint_head endpoint_segment enumeration_type event_port_kind feature_direction feature_group_actual
feature_group_category feature_group_prototype feature_group_spec feature_prototype feature_spec features
flow_chain flow_endpoint flow_implementations flow_kind flow_reference flow_segment flow_spec_body flow_specs
function_name gap identifier implementation_body implementation_choice implementation_name implementation_suffix
import_name in_binding inherit_marker initial_marker inline_properties internal_features
inverse_clause inverse_marker list_type list_value metaclass_reference metaclass_word modal_value mode_binding
mode_reference mode_trigger modes none_statement number number_type numeric_term numeric_value package_name
package_properties parameter_spec port_spec private_section processor_features properties
property_expression property_name property_owner property_reference property_type property_type_designator
prototype_actual prototype_array prototype_bindings prototype_name prototype_spec prototypes public_section
qualified_name range_expression range_type record_assignment record_type record_value reference_term reference_type
qualified_classifier_owner
signed_property_reference
contained_path_segment
refinement requires_modes selection_range sep sign source_endpoint start string_literal subcomponents type_body
type_modes type_range typed_port_kind unit_declaration unit_name units_designator units_list units_type
visibility_declaration
'''.split())


def _attribute_fields(ctx):
    """Synthesize named children from the production's checked binding table."""
    return {field: ctx.node.field(field) for field in ctx.node.bindings}


def _attribute_name(ctx):
    nodes = ctx.get(ctx.node, 'fields')['name']
    return _name(nodes[0]) if nodes and _complete(nodes[0]) else ''


def _attribute_category(ctx):
    if ctx.node.production == 'feature_group_type':
        return 'feature group'
    nodes = ctx.get(ctx.node, 'fields')['category']
    return _text(nodes[0]) if nodes and _complete(nodes[0]) else ''


def _attribute_extension(ctx):
    extensions = ctx.get(ctx.node, 'fields')['extension']
    if not extensions:
        return ''
    targets = ctx.get(extensions[0], 'fields')['target']
    return _name(targets[0]) if targets and _complete(targets[0]) else ''


def _attribute_scope(ctx):
    """Visibility is inherited by section; declarations remain order independent."""
    if ctx.node.production == '__project__':
        return {'package': '', 'imports': (), 'renames': {}, 'import_all': ()}
    if ctx.node.production == 'package_spec':
        # Package-level properties use public visibility when there is a public
        # section (§4.2 N6). A private-only package uses its private visibility.
        sections = {child.production: child for child in ctx.node.children
                    if child.production in {'public_section', 'private_section'}}
        section = sections.get('public_section', sections.get('private_section'))
        if section is not None:
            return ctx.get(section, 'scope')
        return {'package': ctx.get(ctx.node, 'name'), 'imports': (), 'renames': {}, 'import_all': ()}
    if ctx.node.production not in {'public_section', 'private_section'}:
        parent = ctx.parent()
        while parent is not None:
            if (parent.production, 'scope') in HANDLERS:
                return ctx.get(parent, 'scope')
            parent = ctx.parent(parent)
        return ctx.get(ctx.root, 'scope')
    package = ctx.parent()
    while package is not None and package.production != 'package_spec':
        package = ctx.parent(package)
    if package is None:
        raise RuleDefinitionError('A visibility section requires a package parent')
    imports, import_all, renames = set(), set(), {}
    if ctx.node.production == 'private_section':
        package_name = ctx.get(package, 'name')
        # Reading project membership is a tracked dependency: a public part
        # arriving in a later file invalidates this private scope's attributes.
        public = next((section for unit in ctx.project.all_units for part in _find(unit, 'package_spec')
                       if ctx.get(part, 'name') == package_name
                       for section in _direct(part, 'public_section')), None)
        if public is not None:
            inherited = ctx.get(public, 'scope')
            imports.update(inherited['imports'])
            import_all.update(inherited['import_all'])
            renames.update(inherited['renames'])
    # §4.2 N5/N17: public imports and aliases are inherited into private, never
    # vice versa. A later private import cannot rescue an earlier public use.
    for declaration in _find(ctx.node, 'import_declaration'):
        names = ctx.get(declaration, 'fields')['names']
        imports.update(_name(name) for name in names if _complete(name))
    for alias in (n for n in _walk(ctx.node) if n.production in {'package_alias', 'classifier_alias', 'wildcard_alias'}):
        fields = ctx.get(alias, 'fields')
        target = next(iter(fields['target']), None)
        if not _complete(target):
            continue
        if alias.production == 'wildcard_alias':
            import_all.add(_name(target))
        else:
            name = next(iter(fields['name']), None)
            renames[_name(name) if name else _name(target).rsplit('::', 1)[-1]] = _name(target)
    return {'package': ctx.get(package, 'name'), 'imports': tuple(sorted(imports)),
            'renames': renames, 'import_all': tuple(sorted(import_all)),
            'section': 'private' if ctx.node.production == 'private_section' else 'public'}


def _attribute_signature(ctx):
    name, category = ctx.get(ctx.node, 'name'), ctx.get(ctx.node, 'category')
    if not name or not category:
        return None
    scope = ctx.get(ctx.node, 'scope')
    parent, private = ctx.parent(), False
    while parent is not None and parent.production != 'package_spec':
        private |= parent.production == 'private_section'
        parent = ctx.parent(parent)
    return {'key': scope['package'] + '::' + name, 'name': name, 'category': category,
            'implementation': ctx.node.production == 'component_implementation',
            'private': private, 'extension': ctx.get(ctx.node, 'extension'), 'node': ctx.node}


def _attribute_declarations(ctx):
    # Collect the whole package before resolving any references. A declaration
    # later in this file, or a later project file, participates in the same Σ.
    return tuple(signature for node in _walk(ctx.node)
                 if node.production in _CLASSIFIER_PRODUCTIONS
                 for signature in (ctx.get(node, 'signature'),) if signature is not None)


def _attribute_symbols(ctx):
    symbols = {}
    for unit in ctx.project.all_units:
        for package in _find(unit, 'package_spec'):
            for signature in ctx.get(package, 'declarations'):
                # Duplicate-name diagnostics are emitted separately; preserve
                # the first signature so an illegal duplicate cannot replace it.
                symbols.setdefault(signature['key'], signature)
    return symbols


def _attribute_reference_key(ctx):
    if not _complete(ctx.node):
        return None
    reference, scope = _name(ctx.node), ctx.get(ctx.node, 'scope')
    renames, package = scope['renames'], scope['package']
    if '::' not in reference:
        if reference in renames:
            reference = renames[reference]
        elif '.' in reference and reference.split('.', 1)[0] in renames:
            base, suffix = reference.split('.', 1)
            reference = renames[base] + '.' + suffix
        else:
            local = package + '::' + reference
            if not isinstance(ctx.lookup('symbols', local), Missing):
                return local
            matches = tuple(key for prefix in scope['import_all']
                            for key in (prefix + '::' + reference,)
                            if not isinstance(ctx.lookup('symbols', key), Missing))
            return matches[0] if len(matches) == 1 else local
    target_package, name = reference.rsplit('::', 1)
    return renames.get(target_package, target_package) + '::' + name


def _attribute_reference_target(ctx):
    key = ctx.get(ctx.node, 'reference_key')
    reference = _name(ctx.node)
    if key is not None and '::' not in reference and '.' not in reference:
        owner = ctx.parent()
        while owner is not None and owner.production not in _CLASSIFIER_PRODUCTIONS:
            owner = ctx.parent(owner)
        scopes, seen = ([owner] if owner is not None else []), set()
        while scopes:
            scope = scopes.pop()
            if id(scope) in seen:
                continue
            seen.add(id(scope))
            # A component-local formal shadows package classifiers. This
            # reference attribute must agree with _Check.resolve; otherwise
            # the independent category constraint could reject a valid formal
            # merely because an unrelated package classifier shares its name.
            formals = _section_items(scope, 'prototypes', 'prototype')
            if any(_field(formal, 'declaration_name') == reference for formal in formals):
                return None  # prototype-aware category/binding checks run in resolve
            signature = ctx.get(scope, 'signature')
            if signature is None:
                continue
            ancestor_keys = []
            if signature['implementation']:
                ancestor_keys.append(signature['key'].rsplit('.', 1)[0])
            for extension in _direct(scope, 'type_extension') + _direct(scope, 'implementation_extension'):
                base = _first(extension, 'classifier_reference')
                if base is not None:
                    ancestor_keys.append(ctx.get(base, 'reference_key'))
            if signature['private']:
                public = ctx.lookup('symbols', signature['key'])
                if not isinstance(public, Missing) and public['node'] is not scope:
                    scopes.append(public['node'])
            for ancestor_key in ancestor_keys:
                ancestor = ctx.lookup('symbols', ancestor_key) if ancestor_key is not None else None
                if ancestor is None or isinstance(ancestor, Missing) or not _complete(ancestor['node']):
                    return None  # a future/incomplete ancestor may still declare the formal
                scopes.append(ancestor['node'])
    return None if key is None else ctx.lookup('symbols', key)


def _attribute_result(ctx, status, rule, message, clause):
    node = ctx.node
    return RuleResult(status, rule, message, node.source, node.start, node.end,
                      f'{STANDARD} {clause}', OSATE_SOURCE)


def _attribute_end_constraint(ctx):
    name = ctx.get(ctx.node, 'name')
    production = ('end_package_name' if ctx.node.production == 'package_spec' else
                  'end_implementation_name' if ctx.node.production == 'component_implementation' else 'end_name')
    if name:
        for ending in _find(ctx.node, production):
            value = _name(ending)
            if value and ((_complete(ending) and value != name) or
                          (not _complete(ending) and not name.startswith(value))):
                return (_attribute_result(ctx, Status.CONFLICT, 'name.matching_end',
                    f'Closing name {value!r} cannot match {name!r}', '§4.3/§4.4/§4.2'),)
    return (_attribute_result(ctx, Status.SATISFIED, 'name.matching_end',
                              'Available closing-name characters match the opening declaration', '§4.3/§4.4/§4.2'),)


def _attribute_category_constraint(ctx):
    """A reference's expected classifier domain is inherited from its use."""
    expected, implementation = None, None
    parent = ctx.parent()
    if parent is not None:
        if parent.production in {'port_spec', 'parameter_spec'}:
            expected = {'data'}
        elif parent.production == 'feature_group_spec':
            expected = {'feature group'}
        elif parent.production in {'subcomponent', 'component_prototype'}:
            category = _first(parent, 'category')
            if _complete(category):
                expected = {_text(category)}
        elif parent.production == 'access_spec':
            words = _tokens(parent.text)
            if 'access' in words:
                expected = {' '.join(word for word in words[:words.index('access')]
                                     if word not in {'requires', 'provides'})}
        elif parent.production in {'type_extension', 'implementation_extension'}:
            owner = ctx.parent(parent)
            while owner is not None and owner.production not in _CLASSIFIER_PRODUCTIONS:
                owner = ctx.parent(owner)
            if owner is not None:
                category = ctx.get(owner, 'category')
                if category:
                    expected = {category, 'abstract'}
                    implementation = parent.production == 'implementation_extension'
    target = ctx.get(ctx.node, 'target')
    if expected and target is not None and not isinstance(target, Missing):
        if target['category'] not in expected or (implementation is not None and target['implementation'] != implementation):
            return (_attribute_result(ctx, Status.CONFLICT, 'reference.attribute_category',
                f"{target['key']} has incompatible classifier category or kind; expected {sorted(expected)}", '§4.3/§4.4/§8'),)
    # Missing names remain obligations in _Check.resolve, which also knows
    # prototype substitutions and whether the declaring package has closed.
    return (_attribute_result(ctx, Status.SATISFIED, 'reference.attribute_category',
                              'No known classifier conflicts with this reference domain', '§4.3/§4.4/§8'),)


_CLASSIFIER_PRODUCTIONS = frozenset({'component_type', 'component_implementation', 'feature_group_type'})
# This independent inventory is deliberately NOT derived from BINDINGS. A new
# production must explicitly declare its attribute obligations here as well as
# its field layout; merely adding a binding cannot silently opt out of review.
REQUIRED_ATTRIBUTES = {
    'internal_feature': ('fields',),
    'processor_feature': ('fields',),
    'package_spec': ('fields', 'name', 'scope', 'declarations', 'end_constraint'),
    'import_declaration': ('fields',),
    'package_alias': ('fields',),
    'classifier_alias': ('fields',),
    'wildcard_alias': ('fields',),
    'component_type': ('fields', 'name', 'category', 'extension', 'scope', 'signature', 'end_constraint'),
    'component_implementation': ('fields', 'name', 'category', 'extension', 'scope', 'signature', 'end_constraint'),
    'feature_group_type': ('fields', 'name', 'category', 'extension', 'scope', 'signature', 'end_constraint'),
    'component_end': ('fields',),
    'implementation_end': ('fields',),
    'type_extension': ('fields',),
    'implementation_extension': ('fields',),
    'feature': ('fields',),
    'subcomponent': ('fields',),
    'connection': ('fields',),
    'connection_body': ('fields',),
    'property_association': ('fields',),
    'property_set': ('fields', 'name', 'end_constraint'),
    'property_type_declaration': ('fields',),
    'property_definition': ('fields',),
    'property_constant': ('fields',),
    'property_value': ('fields',),
    'unit_conversion': ('fields',),
    'record_field': ('fields',),
    'array_dimension': ('fields',),
    'array_selection': ('fields',),
    'prototype': ('fields',),
    'prototype_binding': ('fields',),
    'subprogram_call': ('fields',),
    'mode': ('fields',),
    'mode_transition': ('fields',),
    'in_modes': ('fields',),
    'component_in_modes': ('fields',),
    'flow_spec': ('fields',),
    'flow_implementation': ('fields',),
    'end_to_end_flow': ('fields',),
    'annex_library': ('fields',),
    'annex_subclause': ('fields',),
}
# Every declared binding has a real synthesized field handler consumed by the
# checker. The important equations below also depend on each other explicitly:
# declarations -> signatures -> scope; target -> reference_key -> scope/Σ.
HANDLERS = {(production, 'fields'): _attribute_fields for production in BINDINGS}
HANDLERS.update({(production, 'scope'): _attribute_scope for production in BINDINGS})
HANDLERS.update({(production, 'name'): _attribute_name for production, fields in BINDINGS.items() if 'name' in fields})
HANDLERS.update({(production, 'category'): _attribute_category for production in _CLASSIFIER_PRODUCTIONS})
HANDLERS.update({(production, 'extension'): _attribute_extension for production in _CLASSIFIER_PRODUCTIONS})
HANDLERS.update({(production, 'signature'): _attribute_signature for production in _CLASSIFIER_PRODUCTIONS})
HANDLERS.update({(production, 'end_constraint'): _attribute_end_constraint
                 for production in _CLASSIFIER_PRODUCTIONS | {'package_spec', 'property_set'}})
HANDLERS.update({('__project__', 'scope'): _attribute_scope,
                 ('public_section', 'scope'): _attribute_scope,
                 ('private_section', 'scope'): _attribute_scope,
                 ('__project__', 'symbols'): _attribute_symbols,
                 ('package_spec', 'declarations'): _attribute_declarations,
                 ('classifier_reference', 'scope'): _attribute_scope,
                 ('classifier_reference', 'reference_key'): _attribute_reference_key,
                 ('classifier_reference', 'target'): _attribute_reference_target,
                 ('classifier_reference', 'category_constraint'): _attribute_category_constraint})
CONSTRAINTS = {production: ('end_constraint',) for production in _CLASSIFIER_PRODUCTIONS | {'package_spec', 'property_set'}}
CONSTRAINTS['classifier_reference'] = ('category_constraint',)


def evaluate(project, plan=None, final=False, attributes=None):
    """Evaluate real registered attributes plus the remaining static rules."""
    if attributes is None:
        attributes = AttributeEvaluator(HANDLERS, project, constraints=CONSTRAINTS, inputs={'plan': plan})
    return _Check(project, plan, final or getattr(project, 'final', False), attributes).run()


def _method_regressions():
    """Small language-owned witnesses for a language-neutral offline runner.

    Each prefix must first be accepted. Probe the append against that immutable
    state and compare expected_allowed; expected_rule identifies a rejected
    rule, not a new scientific metric. complete_source is an optional legal
    completion witness beginning with prefix + probe. project selects a source
    or an AADL-PROJECT container. These are rule diagnostics, never requirements
    samples or inputs silently injected into a generation experiment.
    """
    cases = []

    def add(name, prefix, probe, expected_allowed, *, rest=None, rule=None, project=False):
        case = {'name': name, 'prefix': prefix, 'probe': probe,
                'expected_allowed': expected_allowed, 'project': project}
        if rest is not None:
            case['complete_source'] = prefix + probe + rest
        if rule is not None:
            case['expected_rule'] = rule
        cases.append(case)

    header = ('package MethodChecks\npublic\n'
              'system Leaf\nend Leaf;\n'
              'system Root\nend Root;\n')
    members = (header + 'system implementation Root.impl\nsubcomponents\n'
               ' item: system Leaf;\n')
    closing = 'end Root.impl;\nend MethodChecks;\n'
    add('member_open_name_keeps_alternative', members, 'item', True,
        rest='_next: system Leaf;\n' + closing)
    add('member_name_extension', members + 'item', '_next: system Leaf;\n', True, rest=closing)
    add('member_duplicate_at_colon', members + 'item', ':', False, rule='name.unique_member')
    add('member_duplicate_before_category', members, 'item:', False, rule='name.unique_member')
    add('member_duplicate_partial_category', members, 'item: syst', False, rule='name.unique_member')
    add('member_duplicate_category_alternatives', members, 'item: system', False, rule='name.unique_member')
    add('member_duplicate_case_insensitive', members, 'ITEM:', False, rule='name.unique_member')
    add('member_distinct_name_partial_category', members, 'other: syst', True,
        rest='em Leaf;\n' + closing)
    other_scope = (members + 'end Root.impl;\n'
                   'system Other\nend Other;\n'
                   'system implementation Other.impl\nsubcomponents\n')
    add('member_same_name_other_classifier', other_scope, 'item: system Leaf;\n', True,
        rest='end Other.impl;\nend MethodChecks;\n')
    inherited = (header + 'system implementation Root.base\nsubcomponents\n'
                 ' item: system Leaf;\nend Root.base;\n'
                 'system implementation Root.derived extends Root.base\nsubcomponents\n')
    add('member_inherited_refinement_prefix', inherited, 'item:', True,
        rest=' refined to system Leaf;\nend Root.derived;\nend MethodChecks;\n')
    add('member_inherited_refinement_complete', inherited, 'item: refined to system Leaf;\n', True,
        rest='end Root.derived;\nend MethodChecks;\n')
    future_base = header + 'system implementation Root.derived extends Root.base\nsubcomponents\n'
    add('member_forward_base_refinement', future_base, 'item: refined to system Leaf;\n', True,
        rest=('end Root.derived;\nsystem implementation Root.base\nsubcomponents\n'
              ' item: system Leaf;\nend Root.base;\nend MethodChecks;\n'))

    connections = ('package MethodChecks\npublic\n'
                   'system Leaf\nfeatures\n'
                   ' accept: in event port;\n emit: out event port;\n'
                   ' duplex: in out event port;\n bad: out event port;\n bad_next: in event port;\n'
                   'end Leaf;\n'
                   'system Root\nfeatures\n'
                   ' incoming: in event port;\n outgoing: out event port;\n'
                   ' outgoing_next: in event port;\n duplex: in out event port;\n'
                   'end Root;\nsystem implementation Root.impl\nsubcomponents\n'
                   ' child: system Leaf;\nconnections\n c: port ')
    add('connection_boundary_out_source_at_arrow', connections + 'outgoing ', '->', False,
        rule='connection.endpoint_direction')
    add('connection_boundary_out_source_before_long_destination', connections + 'outgoing ',
        '-> child.accept_repeated', False, rule='connection.endpoint_direction')
    add('connection_boundary_in_source_at_arrow', connections + 'incoming ', '->', True,
        rest=' child.accept;\n' + closing)
    add('connection_child_out_source', connections + 'child.emit ', '->', True,
        rest=' outgoing;\n' + closing)
    add('connection_child_in_source_at_arrow', connections + 'child.accept ', '->', False,
        rule='connection.endpoint_direction')
    add('connection_bidirectional_requires_in_out', connections + 'incoming ', '<->', False,
        rule='connection.endpoint_direction')
    add('connection_bidirectional_in_out', connections + 'duplex ', '<->', True,
        rest=' child.duplex;\n' + closing)
    add('connection_partial_arrow', connections + 'incoming ', '-', True,
        rest='> child.accept;\n' + closing)
    add('connection_partial_bidirectional_arrow', connections + 'duplex ', '<-', True,
        rest='> child.duplex;\n' + closing)
    add('connection_source_identifier_can_grow', connections, 'outgoing', True,
        rest='_next -> child.accept;\n' + closing)
    add('connection_destination_keeps_viable_branch', connections + 'incoming -> child.', 'bad', True,
        rest='_next;\n' + closing)
    add('connection_destination_fixed_bad_direction', connections + 'incoming -> child.bad', ';', False,
        rule='connection.endpoint_direction')
    add('connection_destination_identifier_extension', connections + 'incoming -> child.bad', '_next;', True,
        rest='\n' + closing)
    add('connection_comment_before_arrow', connections + 'incoming -- source role is not fixed yet\n',
        '->', True, rest=' child.accept;\n' + closing)

    prototype = ('package MethodChecks\npublic\nprocessor Actual\nend Actual;\n'
                 'system Leaf\nfeatures\n emit: out event port;\nend Leaf;\n'
                 'system Root\nprototypes\n Actual: system;\nfeatures\n'
                 ' outgoing: out event port;\nend Root;\n'
                 'system implementation Root.impl (Actual => system Leaf)\nsubcomponents\n'
                 ' child: system Actual;\nconnections\n c: port child.emit ')
    add('connection_prototype_shadows_package_classifier', prototype, '->', True,
        rest=' outgoing;\n' + closing)

    # The later declaration in this same file supplies a genuinely unresolved
    # source classifier. Keep the forward obligation while constructing a peer
    # system; no hierarchy-based declaration ordering or second file is needed.
    source = ('package P\npublic\n'
              'system Root\nfeatures\n outgoing: out event port;\nend Root;\n'
              'system implementation Root.impl\nsubcomponents\n'
              ' child: system Leaf.impl;\nconnections\n c: port child.emit')
    project_prefix = 'AADL-PROJECT 1\nFILE\n|' + source.replace('\n', '\n|')
    project_rest = (' outgoing;\n|end Root.impl;\n|system Leaf\n|features\n'
                    '| emit: out event port;\n|end Leaf;\n'
                    '|system implementation Leaf.impl\n|end Leaf.impl;\n'
                    '|end P;\nEND_FILE\nEND_PROJECT\n')
    add('connection_forward_source_same_file', project_prefix, ' ->', True,
        rest=project_rest, project=True)
    forward = ('package DeferredCheck public system Main end Main; '
               'system implementation Main.base subcomponents '
               'child: system Later.base; end Main.base; ')
    add('same_file_forward_implementation_closes_obligation', forward,
        'system Later end Later; system implementation Later.base end Later.base; ', True,
        rest='end DeferredCheck; ')
    add('same_file_forward_wrong_category_is_conflict', forward,
        'process Later end Later; process implementation Later.base end Later.base; ', False,
        rule='reference.category')
    # The active profile generates one file containing one model unit. Reject
    # a second package before it can be
    # mistaken for a locally valid complete candidate.
    one_unit = 'package FileProfile\npublic\nsystem Item\nend Item;\nend FileProfile;\n'
    add('file_profile_rejects_second_package', one_unit, 'package Another ', False)
    add('file_profile_allows_trailing_comment', one_unit, '-- complete unit\n', True, rest='')
    # A fixed category word is stable at EOF even when its lexer uses a regex.
    # Reject the last character that proves the conflict, without banning a
    # valid unfinished keyword in a different owning category.
    device_owner = ('package PrefixCategory public device D end D; '
                    'device implementation D.i subcomponents x: devic')
    add('fixed_category_eof_conflict', device_owner, 'e', False, rule='containment.category')
    system_owner = ('package PrefixCategory public device D end D; system S end S; '
                    'system implementation S.i subcomponents x: devic')
    add('fixed_category_eof_valid_owner', system_owner, 'e', True,
        rest=' D; end S.i; end PrefixCategory;')
    add('fixed_category_mixed_case_conflict', device_owner[:-5] + 'DeViC', 'E', False,
        rule='containment.category')
    return tuple(cases)


METHOD_REGRESSIONS = _method_regressions()


def validate_instance(path):
    """Check explicit instance obligations on the exact OSATE-produced artifact.

    This is intentionally separate from declaration checking. An empty system
    implementation may instantiate successfully in OSATE but cannot satisfy
    AS5506D 13.1 C9. No scheduling or runtime behavioral result is inferred.
    """
    from pathlib import Path
    import xml.etree.ElementTree as ET
    path = Path(path)
    results = []
    def emit(status, rule, message, clause):
        results.append(RuleResult(status, rule, message, source=str(path),
                                  standard=f'{STANDARD} {clause}', osate=OSATE_SOURCE))
    try:
        document = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        emit(Status.CONFLICT, 'instance.readable_xml', str(exc), '§13')
        return SemanticReport(tuple(results))
    local = lambda tag: tag.rsplit('}', 1)[-1]
    instance_tag = '{http://aadl.info/AADL/2.0/instance}SystemInstance'
    root, root_fragment = document, '/'
    if document.tag == '{http://www.omg.org/XMI}XMI':
        candidates = [(i, node) for i, node in enumerate(document) if node.tag == instance_tag]
        if len(candidates) != 1:
            emit(Status.CONFLICT, 'instance.system_root', 'XMI must contain exactly one system instance', '§13.1')
            return SemanticReport(tuple(results))
        ordinal, root = candidates[0]
        # EMF fragments in a multi-root resource begin with the actual root
        # ordinal (/0/@...), not the single-root //@... form. Other top-level
        # objects describe data classifiers and cannot satisfy this system's C9.
        root_fragment = '/' + str(ordinal)
    if root.tag != instance_tag or root.get('category', '').lower() != 'system':
        emit(Status.CONFLICT, 'instance.system_root', 'The artifact must have a system root', '§13.1')
        return SemanticReport(tuple(results))
    components = [root] + [n for n in root.iter() if local(n.tag) == 'componentInstance']
    categories = {n.get('category', '').replace('_', ' ').lower() for n in components}
    missing = {'thread', 'processor', 'memory'} - categories
    if missing:
        emit(Status.CONFLICT, 'instance.required_categories',
             'A complete system instance requires at least one of each: ' + ', '.join(sorted(missing)), '§13.1 C9')
    for component in components:
        classifier = next((n for n in component if local(n.tag) == 'classifier'), None)
        if classifier is None or not classifier.get('href'):
            emit(Status.CONFLICT, 'instance.classifier_resolved',
                 f'Instance {component.get("name", "<unnamed>")} has no resolved classifier', '§13.1 C1')
        if component.get('category', '').lower() == 'process' and not any(
                n is not component and local(n.tag) == 'componentInstance' and n.get('category', '').lower() == 'thread'
                for n in component.iter()):
            emit(Status.CONFLICT, 'instance.process_has_thread',
                 f'Process {component.get("name", "<unnamed>")} contains no thread', '§13.1 C1')
    # Build EMF containment fragments using each child's feature-specific index.
    # This checks links in the instance document, not references back to source
    # .aadl classifiers (those have already been linked by OSATE).
    fragments = {root_fragment: root}
    if root_fragment == '/':
        fragments[''] = root
    def index(element, fragment):
        counts = {}
        for child in element:
            feature = local(child.tag)
            count = counts.get(feature, 0)
            counts[feature] = count + 1
            child_fragment = fragment + '/@' + feature + '.' + str(count)
            fragments[child_fragment] = child
            fragments[child_fragment.lstrip('/')] = child
            index(child, child_fragment)
    index(root, root_fragment)
    for connection in (n for n in root.iter() if local(n.tag) == 'connectionInstance'):
        for endpoint in ('source', 'destination'):
            reference = connection.get(endpoint)
            if not reference:
                emit(Status.CONFLICT, 'instance.connection_endpoint',
                     f'Connection {connection.get("name", "<unnamed>")} lacks {endpoint}', '§13.1 C3')
            elif reference.startswith('/') and reference not in fragments and reference.lstrip('/') not in fragments:
                emit(Status.CONFLICT, 'instance.connection_endpoint',
                     f'Connection endpoint {reference} is not in this instance tree', '§13.1 C3')
    if not results:
        emit(Status.SATISFIED, 'instance.declared_scope', 'The documented instance checks are satisfied', '§13.1 C1/C9')
    return SemanticReport(tuple(results), {'implemented_scope': METADATA['instance_scope'],
        'unsupported_checks': METADATA['instance_unsupported_checks'], 'behavior_verified': False})
