package build.jenesis.repository.format;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A small SVG icon a {@link RepositoryFormat} embeds in its own module and lends to the console through
 * {@link RepositoryFormat#icon()}: a uniform-square, self-contained document (one common {@code viewBox},
 * {@code currentColor}-friendly so it inverts with the light/dark theme) drawn only from permissively-licensed
 * icon sets, its source and licence recorded next to the module. The bytes are metadata-sized - a brand mark, not
 * an artifact - so they ride whole rather than through the streaming store; a server icon endpoint serves them
 * immutable and cached, falling back to a neutral mark for a format whose {@link RepositoryFormat#icon()} is
 * empty. The core stays icon-agnostic: it holds this contract, never an icon of its own.
 *
 * @param svg       the SVG document bytes, embedded in and owned by the format's module
 * @param mediaType the content type the icon is served as, always {@link #SVG_MEDIA_TYPE}
 */
public record IconResource(byte[] svg, String mediaType) {

    /** The single media type an SVG icon is served as. */
    public static final String SVG_MEDIA_TYPE = "image/svg+xml";

    public IconResource {
        Objects.requireNonNull(svg, "svg");
        Objects.requireNonNull(mediaType, "mediaType");
    }

    /**
     * The icon for an SVG document a format embeds as a constant in its own module - a uniform-square,
     * {@code currentColor}-friendly mark from a permissively-licensed set. The text is encoded UTF-8, so the bytes
     * the endpoint serves are exactly the document the format declared.
     */
    public static IconResource svg(String document) {
        return new IconResource(document.getBytes(StandardCharsets.UTF_8), SVG_MEDIA_TYPE);
    }
}
