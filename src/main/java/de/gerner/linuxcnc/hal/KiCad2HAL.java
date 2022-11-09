/**
 * Copyright (c) 2022 Thomas Gerner
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. All advertising materials mentioning features or use of this software
 *    must display the following acknowledgment:
 *      This product includes software developed by Thomas Gerner.
 * 4. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package de.gerner.linuxcnc.hal;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import de.gerner.kicad.binding.CompType;
import de.gerner.kicad.binding.ExportType;
import de.gerner.kicad.binding.FieldType;
import de.gerner.kicad.binding.LibpartType;
import de.gerner.kicad.binding.NetType;
import de.gerner.kicad.binding.NodeType;
import de.gerner.kicad.binding.PinType;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;

/**
 * @author thomas
 *
 */
public class KiCad2HAL
{
  private static final String SEQUENCE2 = "sequence";
  private static final String ORDER = "order";
  private static final String PARAMETER = "PARAMETER";
  private static final String THREAD = "thread";
  private static final String INPUT = "input";
  private static final String OUTPUT = "output";
  private static final String LOADUSR = "loadusr";
  private static final String LOADRT = "loadrt";
  private static final String BB_GPIO = "bb_gpio.";
  
  private ExportType kicadExport;
  private PrintStream out;
  private Map<String, CompType> compMap;
  
  public KiCad2HAL(ExportType kicadExport)
  {
    this(kicadExport, System.out);
  }
  
  public KiCad2HAL(ExportType kicadExport, PrintStream out)
  {
    this.out = out;
    this.kicadExport = kicadExport;
    compMap = new HashMap<>();
    
    for (CompType comp : kicadExport.getComponents().getComp()) {
      compMap.put(comp.getRef(), comp);
    }
  }
  
  public void printHeader()
  {
    out.println("# HAL config file created by KiCad2HAL");
    out.print("# Source file:  ");
    out.println(kicadExport.getDesign().getSource());
    out.print("# Created by:   ");
    out.println(kicadExport.getDesign().getTool());
    out.print("# Created at:   ");
    out.println(kicadExport.getDesign().getDate());
    out.println("#");
    
    kicadExport.getDesign().getSheet().forEach(sheet -> {
      out.print("# Sheet #");
      out.println(sheet.getNumber());
      out.print("#   Title:     ");
      out.println(sheet.getTitleBlock().getTitle());
      out.print("#   Company:   ");
      out.println(sheet.getTitleBlock().getCompany());
      out.print("#   Created:   ");
      out.println(sheet.getTitleBlock().getDate());
      out.print("#   Source:    ");
      out.println(sheet.getTitleBlock().getSource());
      out.print("#   Revision:  ");
      out.println(sheet.getTitleBlock().getRev());
      sheet.getTitleBlock().getComment().forEach(comment -> {
        if (comment.getValue() != null && !comment.getValue().isEmpty()) {
          out.print("#   Comment:   ");
          out.println(comment.getValue());
        }
      });
    });
  }
  
  public void printModules()
  {
    printSectionIntro("Load realtime and userspace modules");
    
    // first look for userspace modules, order them by index:
    ArrayList<String> usermodules = new ArrayList<>();
    findAndPrintModulesToLoad(usermodules, LOADUSR);
    
    // look for realtime modules to load, order them by index
    ArrayList<String> rtmodules = new ArrayList<>();
    findAndPrintModulesToLoad(rtmodules, LOADRT);
    
    // look for other realtime components connected to a thread
    for (CompType comp : kicadExport.getComponents().getComp()) {
      if (!THREAD.equals(comp.getLibsource().getPart())) {
        continue;
      }
      String thread = comp.getRef();
      // look for the nets connected to the thread
      ArrayList<NodeType> threadNodes = new ArrayList<>();
      kicadExport.getNets().getNet().forEach(net -> {
        for (NodeType node : net.getNode()) {
          if (thread.equals(node.getRef())) {
            threadNodes.addAll(net.getNode());
            break;
          }
        }
      });
      // count components of the same part connected to this thread
      Map<String, AtomicInteger> threadComps = new HashMap<>();
      threadNodes.forEach(node -> {
        if (INPUT.equals(node.getPintype())) {
          String part = compMap.get(node.getRef()).getLibsource().getPart();
          if (!isLoadrt(part)) { // only components not already loaded
            // KiCad may add some instance ID to the part name. However, all
            // parts must end with a dot. Remove the dot and everything behind it
            part = part.substring(0, part.indexOf("."));
            AtomicInteger value = threadComps.putIfAbsent(part, new AtomicInteger(1));
            if (value != null) value.incrementAndGet();
          }
        }
      });
      // print loadrt components
      threadComps.forEach((key, value) -> {
        out.format("loadrt %-20s count=%d", key, value.get());
        out.println();
      });
    }
  }
  
  public void printThreadHooks()
  {
    printSectionIntro("Hook functions into threads");

    for (CompType comp : kicadExport.getComponents().getComp()) {
      if (!THREAD.equals(comp.getLibsource().getPart())) {
        continue;
      }
      String threadRef = comp.getRef();
      String threadName = comp.getValue();
      String position = null;
      for (NetType net : kicadExport.getNets().getNet()) {
        NetType foundNet = null;
        for (NodeType node : net.getNode()) {
          if (threadRef.equals(node.getRef())) {
            foundNet = net;
            position = getPinFunction(node, THREAD);
            break;
          }
        }
        if (foundNet == null)
          continue;
        // extract hooks
        Map<String, String> hooks = new LinkedHashMap<>();
        for (NodeType node : foundNet.getNode()) {
          if (INPUT.equals(node.getPintype())) {
            CompType hookComp = compMap.get(node.getRef());
            String hookValue;
            if (isLoadrt(hookComp.getLibsource().getPart())) {
              hookValue = getPinFunction(node, hookComp.getLibsource().getPart());
            } else {
              hookValue = hookComp.getValue();
            }
            if (position.isEmpty()) {
              hooks.put(hookValue, String.format("addf %-20s\t%s", hookValue, threadName));
            } else {
              hooks.put(hookValue, String.format("addf %-20s\t%-10s %s", hookValue, threadName, position));
            }
          }
        }
        // check if there is a sequence defined
        CompType threadComp =compMap.get(threadRef);
        String[] sequence = null;
        if (threadComp.getFields() != null) {
          for (FieldType field : threadComp.getFields().getField()) {
            String fieldName = field.getName();
            if (ORDER.equalsIgnoreCase(fieldName) || SEQUENCE2.equalsIgnoreCase(fieldName)) {
              sequence = field.getValue().split("[,; ]+");
              break;
            }
          }
          if (sequence != null) {
            for (String hookName : sequence) {
              Iterator<Map.Entry<String, String>> iter = hooks.entrySet().iterator();
              while (iter.hasNext()) {
                Map.Entry<String, String> entry = iter.next();
                if (entry.getKey().startsWith(hookName)) {
                  out.println(entry.getValue());
                  iter.remove();
                }
              }
            }
          }
        }
        // print remaining hooks
        hooks.forEach((name, msg) -> out.println(msg));
      }    
    }
  }
  
  public void printParameters()
  {
    printSectionIntro("Set parameters");
    // parameter nets have only 2 nodes (one is the parameter) and the net
    // is not manually named (name doesn't start with /)
    for (NetType net : kicadExport.getNets().getNet()) {
      if (net.getNode().size() != 2 || net.getName().startsWith("/"))
        continue;
      String value = null;
      String pinName = null;
      for (NodeType node : net.getNode()) {
        CompType comp = compMap.get(node.getRef());
        if (PARAMETER.equals(comp.getLibsource().getPart())) {
          value = comp.getValue();
        } else {
          String partName = comp.getLibsource().getPart();
          String pinfunction = getPinFunction(node, partName);
          if (isLoadrt(partName)) {
            pinName = pinfunction;
          } else {
            pinName = buildFullName(comp.getValue(), pinfunction);
          }
        }
      }
      if (value != null && pinName != null) {
        out.format("setp %-20s\t%s", pinName, value);
        out.println();
      }
    }
  }
  
  public void printNetsAndSignals()
  {
    printSectionIntro("Connect component pins with nets");
    // nets have a net name starting with /
    // nets have one output and one ore more inputs
    // signals set a value to the net
    Map<String, NodeType> signalNet = new HashMap<>();
    for (NetType net : kicadExport.getNets().getNet()) {
      if (net.getNode().size() < 2 || !net.getName().startsWith("/"))
        continue;
      String netName = net.getName().substring(1);
      NodeType outputNode = null;
      ArrayList<NodeType> inputNodes = new ArrayList<>();
      for (NodeType node : net.getNode()) {
        if (OUTPUT.equals(node.getPintype())) {
          if (outputNode == null) {
            outputNode = node;
          } else {
            String msg = "# WARNING: multiple outputs connected to net " + netName;
            out.println(msg);
            System.err.println(msg);
          }
        } else {
          inputNodes.add(node);
        }
      }
      if (outputNode == null || inputNodes.size() == 0) {
        String msg = "# No suitable pins connected to net " + netName;
        out.println(msg);
        System.err.println(msg);
      } else {
        out.format("net %s", netName);
        CompType outputComp = compMap.get(outputNode.getRef());
        if (PARAMETER.equals(outputComp.getLibsource().getPart())) {
          signalNet.put(net.getName(), outputNode);
        } else {
          String pinfunction = getPinFunction(outputNode, outputComp.getLibsource().getPart());
          String pinName = buildFullName(outputComp.getValue(), pinfunction);
          out.format(" %s =>", pinName);
        }
        inputNodes.forEach(node -> {
          CompType inputComp = compMap.get(node.getRef());
          String pinfunction = getPinFunction(node, inputComp.getLibsource().getPart());
          String pinName = buildFullName(inputComp.getValue(), pinfunction);
          out.print(" ");
          out.print(pinName);
        });
        out.println();
      }
    }
    if (signalNet.size() > 0) {
      printSectionIntro("Connect signals to nets");
    }
    signalNet.forEach((netName, node) -> {
      CompType comp = compMap.get(node.getRef());
      out.format("sets %-20s %s", netName, comp.getValue());
      out.println();
    });
  }
  
  private void printSectionIntro(String message)
  {
    out.println();
    out.println();
    out.println("####################################################");
    out.print("# ");
    out.println(message);
  }
  
  /**
   * @param modules
   * @param part
   */
  private void findAndPrintModulesToLoad(ArrayList<String> modules, String part)
  {
    kicadExport.getComponents().getComp().forEach(comp -> {
      if (comp.getLibsource().getPart().startsWith(part)) {
        modules.add(comp.getRef());
      }
    });
    modules.sort((String o1, String o2) -> {
      int i1 = getNumber(o1);
      int i2 = getNumber(o2);
      return i2 - i1;
    });
    modules.forEach(ref -> {
      CompType comp = compMap.get(ref);
      out.println(comp.getValue());
    });
  }
  
  private int getNumber(String str)
  {
    int num = 0;
    for (int i = 0; i < str.length(); i++) {
      char ch = str.charAt(i);
      if (Character.isDigit(ch) ) {
        num *= 10 + Character.digit(ch, 10);
      }
    }
    return num;
  }
  
  private boolean isLoadrt(String partName)
  {
    return partName.startsWith(LOADRT);
  }
  
  private String getPinFunction(NodeType node, String part)
  {
    if (node.getPinfunction() != null) {
      return node.getPinfunction();
    }
    String pin = node.getPin();
    for (LibpartType libpart : kicadExport.getLibparts().getLibpart()) {
      if (part.equals(libpart.getPart())) {
        for (PinType pintype : libpart.getPins().getPin()) {
          if (pin.equals(pintype.getNum())) {
            return pintype.getName();
          }
        }
      }
    }
    throw new NullPointerException("Part " + part + " has no pin " + pin);
  }
  
  private String buildFullName(String compName, String pinName)
  {
    if (compName.startsWith(BB_GPIO)) {
      return BB_GPIO + pinName;
    } else if (compName.endsWith(".")) {
      return compName + pinName;
    } else {
      return compName + "." + pinName;
    }
  }
    
  /**
   * @param args
   */
  public static void main(String[] args) 
  {
    if (args.length < 2) {
      System.err.println("Usage: KiCad2HAL inputFileName outputFileName");
      System.exit(-1);
    }
    String inputFileName = args[0];
    String outputFileName = args[1];
    
    try {
      JAXBContext jc = JAXBContext.newInstance("de.gerner.kicad.binding");
      Unmarshaller unmarshaller = jc.createUnmarshaller();

      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(true);
      DocumentBuilder db = dbf.newDocumentBuilder();
      Document doc = db.parse(new File(inputFileName));
      Element  rootElement = doc.getDocumentElement();

      ExportType kicadExport = unmarshaller.unmarshal(rootElement, ExportType.class).getValue();
      
      PrintStream out = new PrintStream(new File(outputFileName));
      KiCad2HAL converter = new KiCad2HAL(kicadExport, out);
      converter.printHeader();
      converter.printModules();
      converter.printThreadHooks();
      converter.printParameters();
      converter.printNetsAndSignals();
    } catch (JAXBException | ParserConfigurationException | SAXException | IOException e) {
      e.printStackTrace();
    }
  }
}
