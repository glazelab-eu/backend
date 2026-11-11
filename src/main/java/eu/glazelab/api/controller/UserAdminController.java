package eu.glazelab.api.controller;

import eu.glazelab.api.dto.AdminUserDtos.AccountStatus;
import eu.glazelab.api.dto.AdminUserDtos.AdminUserPageView;
import eu.glazelab.api.dto.AdminUserDtos.AdminUserView;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The account list (backend guide "Admin user list"), read-only, site admins only: 401 for guests and 403 for everyone else. */
@RestController @RequestMapping("/api/admin/users") @RequiredArgsConstructor
public class UserAdminController {
    private final CurrentUser current;
    private final AppUserRepository users;

    /** The longest search text used; more is cut off. */
    static final int MAX_SEARCH = 100;

    /** The LIKE pattern of a search: stripped, cut to {@link #MAX_SEARCH}, lower case, wildcards and the escape character escaped; blank matches all. */
    static String pattern(String q) {
        String text = q == null ? "" : q.strip();
        if (text.length() > MAX_SEARCH) text = text.substring(0, MAX_SEARCH).strip();
        return "%" + text.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** All accounts by display name (case-insensitive, then ID), in pages of {@link ImageDeletionController#PAGE_SIZE}; one slice query; {@code q} keeps the accounts whose name or email contains it (case-insensitive). */
    @GetMapping @Transactional(readOnly = true)
    AdminUserPageView adminUsers(@RequestParam(defaultValue = "0") int page, @RequestParam(required = false) String q, Authentication auth) {
        current.siteAdmin(auth);
        var result = users.adminList(pattern(q), PageRequest.of(ImageDeletionController.page(page), ImageDeletionController.PAGE_SIZE, Sort.unsorted()));
        return new AdminUserPageView(result.stream().map(row -> new AdminUserView(row.getId(), row.getDisplayName(), row.getEmail(),
                row.getEmailVerified() ? AccountStatus.ACTIVE : AccountStatus.PENDING, row.getSiteAdmin(), row.getCreatedAt())).toList(), result.hasNext());
    }
}
