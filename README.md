## Convert KaCad 6 schema files to LinuxCNC HAL

### Build
The build and execution requires at least Java 11. To build execute

```
mvn clean install
```

on a shell. Usually the build result can be found in the target folder:

```
KiCad2HAL-1.0.0-jar-with-dependencies.jar
```

### KiCad 6 schema editor

In eeschema open File->Export->Netlist and add a new generator
(button "Add Generator..."). Select a title and add

```
java -jar <path to jar>/KiCad2HAL-1.0.0-jar-with-dependencies.jar "%I" "%O.hal"
```

Change the <path to jar> to the folder where you placed the jar.

To convert a LinuxCNC schema to HAL click Button "Export Netlist".