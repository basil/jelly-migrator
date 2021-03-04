package io.jenkins.infra.jelly_migrator;

import de.pdark.decentxml.Attribute;
import de.pdark.decentxml.Document;
import de.pdark.decentxml.Element;
import de.pdark.decentxml.Namespace;
import de.pdark.decentxml.XMLParseException;
import de.pdark.decentxml.XMLParser;
import de.pdark.decentxml.XMLStringSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

public class Main {

  @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "TODO")
  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Usage: jelly-migrator <target>");
    }

    Path target = Paths.get(args[0]);
    if (!Files.isDirectory(target)) {
      throw new IllegalArgumentException(String.format("'%s' must be a directory.", target));
    }

    FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
    Repository repo =
        repositoryBuilder.setGitDir(target.resolve(".git").toFile()).setMustExist(true).build();

    try (RevWalk revWalk = new RevWalk(repo);
        TreeWalk treeWalk = new TreeWalk(repo)) {
      ObjectId id = repo.resolve(Constants.HEAD);
      RevCommit commit = revWalk.parseCommit(id);
      RevTree tree = commit.getTree();
      treeWalk.addTree(tree);
      treeWalk.setRecursive(true);
      while (treeWalk.next()) {
        if (treeWalk.isSubtree()) {
          treeWalk.enterSubtree();
        } else {
          if (treeWalk.getPathString().endsWith(".jelly")) {
            Path path = target.resolve(treeWalk.getPathString());
            processJellyFile(path);
          }
        }
      }
    }
  }

  @SuppressFBWarnings(
      value = {"NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", "PATH_TRAVERSAL_IN"},
      justification = "TODO")
  private static void processJellyFile(Path path) {
    System.out.println(String.format("Processing '%s'...", path));
    boolean changed = false;

    String xml;
    try {
      xml = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    Document document;
    try {
      XMLParser parser = new XMLParser();
      document = parser.parse(new XMLStringSource(xml));
    } catch (XMLParseException e) {
      System.err.println("Warning: failed to process " + path);
      return;
    }

    Element root = document.getRootElement();
    String nsPrefix = getStaplerXmlnsPrefix(root.getAttributeMap());
    if (nsPrefix == null) {
      // no Stapler namespace
      return;
    }

    Namespace ns = document.getNamespace(nsPrefix);
    List<Element> elements = findElementsWithName(root, "include", ns);
    for (Element element : elements) {
      Attribute attr = element.getAttribute("class");
      if (attr != null) {
        attr.setName("clazz");
        changed = true;
      }
    }

    if (changed) {
      try {
        Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), null);
        Files.write(temp, document.toXML().getBytes(StandardCharsets.UTF_8));
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static String getStaplerXmlnsPrefix(Map<String, Attribute> attributes) {
    for (Map.Entry<String, Attribute> attribute : attributes.entrySet()) {
      if (attribute.getKey().startsWith("xmlns:")
          && attribute.getValue().getValue().equals("jelly:stapler")) {
        return attribute.getKey().substring(6);
      }
    }
    return null;
  }

  private static List<Element> findElementsWithName(
      Element rootElement, String elementName, Namespace ns) {
    List<Element> answer = new ArrayList<>();
    if (Objects.equals(elementName, rootElement.getName())
        && Objects.equals(ns, rootElement.getNamespace())) {
      answer.add(rootElement);
    }
    List<Element> children = rootElement.getChildren();
    for (Element child : children) {
      if (Objects.equals(elementName, child.getName())
          && Objects.equals(ns, child.getNamespace())) {
        answer.add(child);
      } else {
        answer.addAll(findElementsWithName(child, elementName, ns));
      }
    }
    return answer;
  }
}
