package eu.glazelab.api.service;

import eu.glazelab.api.repository.AccountTokenRepository;
import eu.glazelab.api.repository.BuddyLinkRepository;
import eu.glazelab.api.repository.BuddyRepository;
import eu.glazelab.api.repository.FavoriteRepository;
import eu.glazelab.api.repository.MembershipRepository;
import eu.glazelab.api.repository.StockRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Rows that only make sense for their account and go with it: personal stock, favourites, memberships, tokens, the
 * buddy link, every buddy pair (accepted or pending, either side; the FKs cascade as well) and the profile picture
 * with every evidence copy of the account's earlier pictures, reported or not ({@code AvatarService.removeOf}: the
 * picture row now, all objects after commit; evidence rows keep only outcome and hashes) and every feedback with
 * its photos ({@code FeedbackService.removeOf}: rows now, objects after commit). The one list for account deletion
 * and the pending-account purge; runs inside the caller's transaction after the account row lock and takes no club lock
 * and no lock of the other account of a pair (buddy writes lock both accounts before the pair row, so they queue
 * behind this account's lock).
 */
@Component @RequiredArgsConstructor
class AccountRows {
    private final StockRepository stocks;
    private final FavoriteRepository favorites;
    private final MembershipRepository memberships;
    private final AccountTokenRepository tokens;
    private final BuddyRepository buddies;
    private final BuddyLinkRepository buddyLinks;
    private final AvatarService avatars;
    private final FeedbackService feedback;

    void delete(UUID userId) {
        stocks.deleteByUser_Id(userId);
        favorites.deleteByUser_Id(userId);
        memberships.deleteByUser_Id(userId);
        tokens.deleteByUser_Id(userId);
        buddies.deleteOf(userId);
        buddyLinks.deleteOf(userId);
        avatars.removeOf(userId);
        feedback.removeOf(userId);
    }
}
