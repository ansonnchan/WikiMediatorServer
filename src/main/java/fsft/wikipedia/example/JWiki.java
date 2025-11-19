package fsft.wikipedia.example;

import io.github.fastily.jwiki.core.*;
import io.github.fastily.jwiki.dwrap.Revision;

import java.util.List;

/*
    A very simple example that illustrates how one can use the
    JWiki API for interacting with Wikipedia.

    You can remove this class for your submission.
    If you do submit this class then it should be compilable.
 */

public class JWiki {
    public static void main(String[] args) {
        Wiki wiki = new Wiki.Builder().withDomain("en.wikipedia.org").build();
        String pageTitle = "Barack Obama";
        System.out.println(wiki.getCategoriesOnPage(pageTitle));
        System.out.println(wiki.getLinksOnPage(pageTitle));
        System.out.println(wiki.search("Hello", 10));
        System.out.println(wiki.search(pageTitle, 5));
        System.out.println(wiki.getCategoriesOnPage(pageTitle));
        List<Revision> rList = wiki.getRevisions(pageTitle, 10, false, null, null);
        if (rList.size() > 0) {
            Revision latestRev = rList.get(0);
            System.out.println(latestRev.user);
            System.out.println(wiki.getContribs(latestRev.user, 10, false, false));
        }
        System.exit(0);
    }
}