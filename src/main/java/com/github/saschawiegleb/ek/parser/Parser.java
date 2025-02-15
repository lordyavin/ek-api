package com.github.saschawiegleb.ek.parser;

import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.github.saschawiegleb.ek.entity.Ad;
import com.github.saschawiegleb.ek.entity.Configuration;
import com.github.saschawiegleb.ek.entity.ImmutableAd;
import com.github.saschawiegleb.ek.entity.ImmutableAd.Builder;
import com.github.saschawiegleb.ek.network.Reader;

import javaslang.collection.HashMap;
import javaslang.collection.List;
import javaslang.collection.Map;
import javaslang.collection.Seq;
import javaslang.control.Either;
import javaslang.control.Option;

public final class Parser {
  private static final ZoneId berlin = ZoneId.of("Europe/Berlin");
  private static final DateTimeFormatter dayMonthYearFormatter =
      DateTimeFormatter.ofPattern("dd.MM.yyyy", new Locale("de"));
  private static final Element defaultElement = new Element("DEFAULT");
  private static final Elements defaultElements = new Elements();
  private static final Logger logger = Logger.getLogger(Parser.class.getName());

  private final Configuration configuration;

  private Parser(Configuration configuration) {
    this.configuration = Objects.requireNonNull(configuration);
  }

  public static Parser of(Configuration configuration) {
    return new Parser(configuration);
  }

  private static Elements selectAll(Element element, String selector) {
    if (element != null && selector != null) {
      Elements elements = element.select(selector);
      if (elements.size() > 0) {
        return elements;
      }
    }
    return defaultElements;
  }

  private static Element selectFirst(Element element, String selector) {
    Elements elements = selectAll(element, selector);
    if (elements.size() == 1) {
      return elements.first();
    }
    return defaultElement;
  }

  public Seq<Ad> ads(Document ads) {
    Map<Long, Either<String, ZonedDateTime>> elementsById = parseAdEntries(ads);
    return elementsById.map(entry -> readAd(entry._1, entry._2));
  }

  public Seq<Ad> ads(Document ads, long lowerBound) {
    Map<Long, Either<String, ZonedDateTime>> elementsById = parseAdEntries(ads);
    return elementsById.filter(e -> e._1 > lowerBound).map(entry -> readAd(entry._1, entry._2));
  }

  public Seq<Ad> adsLightweight(Document ads) {
    ArrayList<Ad> adList = new ArrayList<>();
    for (Element adListEntry : ads.select(configuration.selector().adListEntryElement())) {
      long id = Long.parseLong(adListEntry.attr(configuration.selector().adListEntryId()));
      Either<String, ZonedDateTime> time = time(adListEntry);
      adList.add(readAdLightweight(id, time, adListEntry));
    }
    return List.ofAll(adList);
  }

  private boolean isAvailable(Document adPage) {
    return adPage.select(configuration.selector().adPageExpiredMessage()).isEmpty()
        && adPage.select(configuration.selector().homeContent()).isEmpty();
  }

  private URL linkById(long id) {
    return configuration.resolvePath("s-anzeige/" + id).get();
  }

  private URL linkByUserId(String id) {
    if (id.startsWith("/pro/")) {
      return configuration.resolvePath(id).get();
    }
    if (id.contains("shop")) {
      return configuration.resolvePath(id).get();
    }
    return configuration.resolvePath("s-bestandsliste.html?userId=" + id).get();
  }

  public Option<URL> nextPage(Document document) {
    Elements pages = document.select(".pagination-pages").first().children();
    List<Element> followingPages =
        List.ofAll(pages).splitAt(element -> element.hasClass("pagination-current"))._2().tail();
    if (followingPages.isEmpty()) {
      return Option.none();
    }
    String path = followingPages.head().attr("href");
    URL url = configuration.resolvePath(path).get();
    return Option.of(url);
  }

  public Map<Long, Either<String, ZonedDateTime>> parseAdEntries(Document adList) {
    Map<Long, Either<String, ZonedDateTime>> map = HashMap.empty();
    for (Element adListEntry : adList.select(configuration.selector().adListEntryElement())) {
      long id = Long.parseLong(adListEntry.attr(configuration.selector().adListEntryId()));
      Either<String, ZonedDateTime> time = time(adListEntry);
      map = map.put(id, time);
    }
    return map;
  }

  public Ad readAd(long id, Either<String, ZonedDateTime> time) {
    ImmutableAd.Builder builder = ImmutableAd.builder();
    builder.id(id);
    builder.time(time);
    // TODO side effect, must be removed
    try {
      Document adPage = Reader.requestDocument(linkById(id)).get();
      if (!isAvailable(adPage)) {
        builder.headline("no longer available");
        logger.info(String.format("Ad with id %d is no longer available.", id));
        return builder.build();
      }

      setAdditionalDetails(builder, adPage);
      setCategory(builder, adPage);
      setDescription(builder, adPage);
      setHeadline(builder, adPage);
      setImages(builder, adPage);
      setLocation(builder, adPage);
      setPrice(builder, adPage);
      if (time.isLeft()) {
        setTime(builder, adPage);
      }
      setVendor(builder, adPage);
      return builder.build();
    } catch (Exception e) {
      logger.log(Level.WARNING, e.getMessage(), e);
      builder.headline("Error fetching ad page: " + e.getMessage());
      return builder.build();
    }
  }

  Ad readAdLightweight(long id, Either<String, ZonedDateTime> time, Element adListEntry) {
    ImmutableAd.Builder builder = ImmutableAd.builder();
    builder.id(id);
    builder.time(time);
    builder.headline(
        selectFirst(adListEntry, configuration.selector().adListEntryHeadline()).ownText());
    builder.description(
        selectFirst(adListEntry, configuration.selector().adListEntryDescription()).ownText());
    builder.price(selectFirst(adListEntry, configuration.selector().adListEntryPrice()).ownText());
    builder.location(
        selectFirst(adListEntry, configuration.selector().adListEntryLocation()).ownText());
    builder.images(
        List.of(
            selectFirst(adListEntry, configuration.selector().adListImage())
                .attr("data-imgsrc")
                .replaceFirst("_9", "_3")));
    return builder.build();
  }

  private void setAdditionalDetails(Builder builder, Document adPage) {
    Elements keys = selectAll(adPage, configuration.selector().adPageAdditionalDetailsKeys());
    Elements values = selectAll(adPage, configuration.selector().adPageAdditionalDetailsValues());

    Map<String, String> details = HashMap.empty();
    for (int i = 3; i < keys.size(); i++) {
      String key = keys.get(i).ownText();
      String value = values.get(i).ownText().trim();
      if (value.isEmpty() && values.get(i).child(0) != null) {
        value = values.get(i).child(0).ownText();
      }
      if (value.isEmpty() && values.get(i).child(0).child(0) != null) {
        value = values.get(i).child(0).child(0).ownText();
      }
      if (value.replaceAll(",", "").trim().isEmpty()) {
        value = "";
        for (Element listEntry : selectAll(values.get(i), "a")) {
          value += listEntry.ownText() + ",";
        }
      }
      details = details.put(key, !value.isEmpty() ? value : values.get(i).child(0).ownText());
    }
    builder.additionalDetails(details);
  }

  private void setCategory(Builder builder, Document adPage) {
    String category[] =
        selectFirst(adPage, configuration.selector().adPageCategory())
            .attr(configuration.selector().adPageCategoryLinkAttribute())
            .split("/");
    if (category.length > 0 && !category[category.length - 1].isEmpty()) {
      int id = Integer.parseInt(category[category.length - 1].substring(1));
      builder.category(configuration.category(id).get());
    }
  }

  private void setDescription(Builder builder, Document adPage) {
    builder.description(
        selectFirst(adPage, configuration.selector().adPageDescription()).ownText());
  }

  private void setHeadline(Builder builder, Document adPage) {
    builder.headline(selectFirst(adPage, configuration.selector().adPageHeadline()).ownText());
  }

  private void setImages(Builder builder, Document adPage) {
    if (!selectFirst(adPage, configuration.selector().adPageImagesAvailable())
        .equals(defaultElement)) {
      Elements images = selectAll(adPage, configuration.selector().adPageImages());
      List<String> imageLinks = List.empty();
      for (Element img : images) {
        String link =
            img.attr(configuration.selector().adPageImagesLinkAttribute())
                .replaceFirst("_72", "_3");
        imageLinks = imageLinks.append(link);
      }
      builder.images(imageLinks);
    }
  }

  private void setLocation(Builder builder, Document adPage) {
    builder.location(selectFirst(adPage, configuration.selector().adPageLocation()).ownText());
  }

  private void setPrice(Builder builder, Document adPage) {
    Element price = selectFirst(adPage, configuration.selector().adPagePrice());
    if (!price.equals(defaultElement)) {
      builder.price(price.ownText().replaceAll("Preis: ", ""));
      return;
    }
    String category = selectFirst(adPage, "#viewad-main > meta").attr("content");
    if (category.equals("Tauschen")
        || category.equals("Zu verschenken")
        || category.equals("Verleihen")) {
      builder.price(category);
      return;
    }

    String jobs = selectFirst(adPage, "#vap-brdcrmb > a:nth-child(2) > span").ownText();
    if (jobs.equals("Jobs")) {
      builder.price("job");
      return;
    }

    builder.price("unknown");
  }

  private void setTime(Builder builder, Document adPage) {
    try {
      Elements children =
          adPage.select(configuration.selector().adPageAttributes()).first().children();
      String time =
          List.ofAll(children)
              .splitAt(element -> element.ownText().contains("Erstellungsdatum"))
              ._2()
              .tail()
              .head()
              .ownText()
              .trim();
      LocalDateTime dateTime = LocalDate.parse(time, dayMonthYearFormatter).atStartOfDay();
      builder.time(Either.right(ZonedDateTime.of(dateTime, berlin)));
    } catch (Exception e) {
      logger.log(Level.WARNING, e.getMessage(), e);
      builder.time(Either.left(e.getMessage()));
    }
  }

  private void setVendor(Builder builder, Document adPage) {
    Element userPageLink = selectFirst(adPage, configuration.selector().adPageVendor());
    if (!userPageLink.equals(defaultElement)) {
      builder.vendorId(
          userPageLink
              .attr(configuration.selector().adPageVendorLinkAttribute())
              .replaceAll("/s-bestandsliste\\.html\\?userId=", ""));
      builder.vendorName(userPageLink.ownText());
      return;
    }
    userPageLink = selectFirst(adPage, configuration.selector().adPageVendorOtherAds());
    if (!userPageLink.equals(defaultElement)) {
      String id =
          userPageLink
              .attr(configuration.selector().adPageVendorLinkAttribute())
              .replaceAll("/s-bestandsliste\\.html\\?userId=", "");
      builder.vendorId(id);
      // TODO side effect, must be removed
      try {
        Document userPage = Reader.requestDocument(linkByUserId(id)).get();
        Element user = selectFirst(userPage, configuration.selector().userPageUsername());
        builder.vendorName(user.ownText());
      } catch (Exception e) {
        logger.log(Level.WARNING, e.getMessage(), e);
        builder.vendorName(String.format("Error fetching vendor page", e.getMessage()));
      }
      return;
    }
    userPageLink = selectFirst(adPage, configuration.selector().adPageVendorShopOtherAds());
    if (!userPageLink.equals(defaultElement)) {
      String id =
          userPageLink
              .attr(configuration.selector().adPageVendorLinkAttribute())
              .replaceAll("/s-bestandsliste\\.html\\?userId=", "");
      builder.vendorId(id);
      // TODO side effect, must be removed
      try {
        Document userPage = Reader.requestDocument(linkByUserId(id)).get();
        Element user = selectFirst(userPage, configuration.selector().userPageShopName());
        builder.vendorName(user.ownText().trim());
      } catch (Exception e) {
        logger.log(Level.WARNING, e.getMessage(), e);
        builder.vendorName(String.format("Error fetching vendor page", e.getMessage()));
      }
      return;
    }
  }

  private Either<String, ZonedDateTime> time(Element adListEntry) {
    try {
      // not available in TopAds
      List<String> time =
          List.of(
              adListEntry
                  .select(configuration.selector().adListEntryTime())
                  .first()
                  .ownText()
                  .split(","));
      LocalDateTime dateTime;
      if (time.head().equals("Heute")) {
        LocalTime t = LocalTime.parse(time.tail().head().trim());
        dateTime = t.atDate(LocalDate.now());
      } else if (time.head().equals("Gestern")) {
        LocalTime t = LocalTime.parse(time.tail().head().trim());
        dateTime = t.atDate(LocalDate.now().minusDays(1));
      } else if (!time.head().trim().isEmpty()) {
        dateTime = LocalDate.parse(time.head().trim(), dayMonthYearFormatter).atStartOfDay();
      } else {
        return Either.left("Ad has no time entry (maybe Top Ad)");
      }
      return Either.right(ZonedDateTime.of(dateTime, berlin));
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, e.getMessage(), e);
      return Either.left(e.getMessage());
    }
  }
}
