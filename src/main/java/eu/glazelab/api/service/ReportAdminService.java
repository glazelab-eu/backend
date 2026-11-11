package eu.glazelab.api.service;

import eu.glazelab.api.dto.ReportDtos.DeviceInfo;
import eu.glazelab.api.dto.ReportDtos.ReportBuildCount;
import eu.glazelab.api.dto.ReportDtos.ReportBuildsView;
import eu.glazelab.api.dto.ReportDtos.ReportDeviceCount;
import eu.glazelab.api.dto.ReportDtos.ReportGroupPageView;
import eu.glazelab.api.dto.ReportDtos.ReportGroupView;
import eu.glazelab.api.dto.ReportDtos.ReportOccurrencePageView;
import eu.glazelab.api.dto.ReportDtos.ReportOccurrenceView;
import eu.glazelab.api.model.ClientReport;
import eu.glazelab.api.repository.ClientReportRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Site admins reading the error reports (backend guide "Error reports"). Callers checked the role. Nothing here reads
 * or returns {@code user_id}: a report is never tied to a person for the reader. The group listing is grouped by
 * fingerprint on the fingerprint index, newest group first; the filters (kind, exact build label, text in the message)
 * narrow the rows before they are grouped, so counts are of the matching rows.
 */
@Service @RequiredArgsConstructor
public class ReportAdminService {
    public static final String REPORT_NOT_FOUND = "Report not found";
    public static final String QUERY_TOO_LONG = "Search for at most 100 characters.";
    public static final int MAX_QUERY = 100;
    /** Occurrences per page: each carries a stack of up to 3000 characters. */
    public static final int OCCURRENCE_PAGE_SIZE = 20;
    /** How many builds the filter offers, and how many browser/OS pairs a group lists. */
    static final int MAX_BUILDS = 50;
    static final int MAX_DEVICES = 8;
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    private final ClientReportRepository reports;
    private final EntityManager entities;

    /** Filters of the group listing; null means no filter. */
    public record Filter(ClientReport.Kind kind, String build, String text) {}

    /** The text filter, stripped; blank is none; 400 beyond {@link #MAX_QUERY}. */
    public static String text(String q) {
        if (q == null || q.isBlank()) return null;
        String text = q.strip();
        if (text.length() > MAX_QUERY) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, QUERY_TOO_LONG);
        return text;
    }

    @Transactional(readOnly = true)
    public ReportGroupPageView groups(Filter filter, int page, int size) {
        var where = new Where(filter);
        Query groups = where.apply(entities.createQuery("select r.fingerprint, r.kind, max(r.message), count(r),"
                + " sum(r.occurrences), min(r.receivedAt), max(r.receivedAt), min(r.viewportWidth), max(r.viewportWidth)"
                + " from ClientReport r" + where.clause + " group by r.fingerprint, r.kind"
                + " order by max(r.receivedAt) desc, r.fingerprint"));
        groups.setFirstResult(page * size).setMaxResults(size + 1);
        List<?> rows = groups.getResultList();
        boolean hasMore = rows.size() > size;
        List<Object[]> shown = rows.stream().limit(size).map(row -> (Object[]) row).toList();
        if (shown.isEmpty()) return new ReportGroupPageView(List.of(), false);
        List<String> fingerprints = shown.stream().map(row -> (String) row[0]).toList();
        var builds = spread("r.buildLabel", fingerprints, where);
        var devices = spread("r.browser, r.os", fingerprints, where);
        var views = new ArrayList<ReportGroupView>();
        for (Object[] row : shown) {
            String fingerprint = (String) row[0];
            views.add(new ReportGroupView(fingerprint, (ClientReport.Kind) row[1], (String) row[2], (Long) row[3],
                    (Long) row[4], (Instant) row[5], (Instant) row[6],
                    builds.getOrDefault(fingerprint, List.of()).stream()
                            .map(r -> new ReportBuildCount((String) r[0], (Long) r[1])).toList(),
                    devices.getOrDefault(fingerprint, List.of()).stream().limit(MAX_DEVICES)
                            .map(r -> new ReportDeviceCount((String) r[0], (String) r[1], (Long) r[2])).toList(),
                    (Integer) row[7], (Integer) row[8]));
        }
        return new ReportGroupPageView(views, hasMore);
    }

    /** Per fingerprint: the distinct values of {@code columns} with their row counts, most frequent first. */
    private Map<String, List<Object[]>> spread(String columns, List<String> fingerprints, Where where) {
        Query query = where.apply(entities.createQuery("select r.fingerprint, " + columns + ", count(r) from ClientReport r"
                + where.clause + (where.clause.isEmpty() ? " where" : " and") + " r.fingerprint in :fingerprints"
                + " group by r.fingerprint, " + columns + " order by count(r) desc, " + columns));
        query.setParameter("fingerprints", fingerprints);
        var byGroup = new LinkedHashMap<String, List<Object[]>>();
        for (Object row : query.getResultList()) {
            Object[] values = (Object[]) row;
            // fingerprint, columns..., count  ->  columns..., count
            byGroup.computeIfAbsent((String) values[0], k -> new ArrayList<>())
                    .add(java.util.Arrays.copyOfRange(values, 1, values.length));
        }
        return byGroup;
    }

    @Transactional(readOnly = true)
    public ReportBuildsView builds() {
        return new ReportBuildsView(reports.builds(PageRequest.of(0, MAX_BUILDS)));
    }

    /** One message's reports, newest first; 404 for a first page that is empty (unknown or deleted message). */
    @Transactional(readOnly = true)
    public ReportOccurrencePageView occurrences(String fingerprint, int page) {
        if (!FINGERPRINT.matcher(fingerprint).matches()) throw notFound();
        var slice = reports.findByFingerprint(fingerprint,
                PageRequest.of(page, OCCURRENCE_PAGE_SIZE, Sort.by("receivedAt", "id").descending()));
        if (page == 0 && slice.isEmpty()) throw notFound();
        return new ReportOccurrencePageView(slice.stream().map(ReportAdminService::view).toList(), slice.hasNext());
    }

    /** Deletes every report of a message (204 in the controller); 404 when there is none. */
    @Transactional
    public void deleteGroup(String fingerprint) {
        if (!FINGERPRINT.matcher(fingerprint).matches() || reports.deleteGroup(fingerprint) == 0) throw notFound();
    }

    /** Deletes one report; 404 when it is unknown (also one the purge or another admin removed first). */
    @Transactional
    public void deleteOne(UUID id) {
        if (reports.deleteOne(id) != 1) throw notFound();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, REPORT_NOT_FOUND);
    }

    private static ReportOccurrenceView view(ClientReport r) {
        var device = new DeviceInfo(r.getBrowser(), r.getBrowserVersion(), r.getOs(), r.getOsVersion(), r.getScreenWidth(),
                r.getScreenHeight(), r.getViewportWidth(), r.getViewportHeight(), r.getPixelRatio(), r.getPointer(),
                r.getLanguage(), r.getStandalone(), r.getOnline(), r.getConnectionType(), r.getServiceWorker(),
                r.getUpdatePending(), r.getReducedMotion(), r.getColorScheme(), r.getTimezoneOffsetMinutes(),
                r.getDeviceMemoryGb());
        return new ReportOccurrenceView(r.getId(), r.getReceivedAt(), r.getOccurredAt(), r.getOccurrences(),
                r.getBuildLabel(), r.getRouteName(), r.getStack(), device);
    }

    /** The where clause of a filter with its bound values (only the filters that are set are part of the statement). */
    private static final class Where {
        final String clause;
        private final Map<String, Object> values = new LinkedHashMap<>();

        Where(Filter filter) {
            var parts = new ArrayList<String>();
            if (filter.kind() != null) { parts.add("r.kind = :kind"); values.put("kind", filter.kind()); }
            if (filter.build() != null) { parts.add("r.buildLabel = :build"); values.put("build", filter.build()); }
            if (filter.text() != null) {
                parts.add("lower(r.message) like :text escape '\\'");
                values.put("text", "%" + filter.text().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                        .replace("%", "\\%").replace("_", "\\_") + "%");
            }
            clause = parts.isEmpty() ? "" : " where " + String.join(" and ", parts);
        }

        Query apply(Query query) {
            values.forEach(query::setParameter);
            return query;
        }
    }
}
