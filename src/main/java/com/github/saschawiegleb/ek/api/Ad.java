package com.github.saschawiegleb.ek.api;

import java.net.URL;
import java.time.LocalDateTime;

import org.immutables.value.Value.Default;
import org.immutables.value.Value.Immutable;

import javaslang.collection.HashMap;
import javaslang.collection.List;
import javaslang.collection.Map;
import javaslang.control.Either;

@Immutable
abstract class Ad {

    @Default
    Map<String, String> additionalDetails() {
        return HashMap.empty();
    }

    @Default
    Category category() {
        return Category.of(0, "All");
    }

    @Default
    String description() {
        return "";
    }

    @Default
    String headline() {
        return "";
    }

    abstract long id();

    @Default
    List<String> images() {
        return List.empty();
    }

    final URL link(Configuration configuration) {
        return configuration.resolvePath("s-anzeige/" + id()).get();
    }

    @Default
    String location() {
        return "";
    }

    @Default
    String price() {
        return "";
    }

    final String searchString() {
        return headline().toLowerCase() + " " + description().toLowerCase();
    }

    @Default
    Either<String, LocalDateTime> time() {
        return Either.left("no time set");
    }

    @Default
    String vendorId() {
        return "";
    }

    @Default
    String vendorName() {
        return "";
    }
}
