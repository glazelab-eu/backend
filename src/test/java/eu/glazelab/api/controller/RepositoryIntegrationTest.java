package eu.glazelab.api.controller;

import eu.glazelab.api.model.ImageVisibility;
import eu.glazelab.api.model.ImageKind;
import eu.glazelab.api.dto.CombinationDtos;
import eu.glazelab.api.dto.GlazeDtos.NewGlaze;
import eu.glazelab.api.dto.GlazeDtos.NewManufacturer;
import java.util.UUID;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.Club;
import eu.glazelab.api.model.Combination;
import eu.glazelab.api.model.ImageRecord;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.repository.ClubRepository;
import eu.glazelab.api.repository.CombinationRepository;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.repository.MembershipRepository;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.hibernate.proxy.HibernateProxy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:repositorytest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate" })
@Transactional
class RepositoryIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired ClubRepository clubs;
    @Autowired MembershipRepository memberships;
    @Autowired CombinationRepository combinations;
    @Autowired ImageRecordRepository images;
    @Autowired CatalogController catalog;
    @Autowired ClubController clubController;
    @Autowired CombinationController recipes;
    @Autowired EntityManager entities;

    @Test void repositoriesPreserveStockLayersAndVisibility() {
        AppUser ownerUser = users.save(new AppUser("owner@example.test", "unused", "Owner"));
        AppUser memberUser = users.save(new AppUser("member@example.test", "unused", "Member"));
        var owner = new UsernamePasswordAuthenticationToken(new eu.glazelab.api.security.AccountPrincipal(ownerUser), "unused", List.of());
        var member = new UsernamePasswordAuthenticationToken(new eu.glazelab.api.security.AccountPrincipal(memberUser), "unused", List.of());

        UUID clubId = clubController.createClub(new eu.glazelab.api.dto.ClubDtos.NewClub("Kiln"), owner).id();
        clubController.updateClub(clubId, new eu.glazelab.api.dto.ClubDtos.ClubSettings("Kiln", "", true), owner);
        var invitation = new eu.glazelab.api.dto.ClubDtos.InviteInput(clubController.regenerateInvite(clubId, owner).token());
        clubController.joinClub(invitation, member);
        clubController.joinClub(invitation, member);
        assertEquals(1, memberships.findByUser_Id(memberUser.getId()).size());
        assertEquals(ownerUser.getId(), clubController.clubs(owner).getFirst().ownerId());

        UUID blueId = catalog.addGlaze(new NewGlaze(null,
                new NewManufacturer("Maker", "MAKER"), "Blue", "PC-20", null, null, null, null), owner).id();
        UUID makerId = catalog.glazes(owner).getFirst().manufacturer().id();
        UUID whiteId = catalog.addGlaze(new NewGlaze(makerId, null, "White", "PC-21", null, List.of("Glossy"), null, null), owner).id();
        catalog.saveStock(new CatalogController.StockInput(blueId, 450, null, "personal", null), owner);
        catalog.saveStock(new CatalogController.StockInput(whiteId, 200, null, "club", clubId), owner);
        assertEquals(2, catalog.stock(owner).size());
        assertEquals(1, catalog.stock(member).size());
        assertTrue(catalog.stock(owner).stream().anyMatch(s -> s.glazeId().equals(blueId)
                && s.quantityG() == 450));

        var layers = List.of(new CombinationDtos.StepInput(null, blueId, 2, null, "Brush", null),
                new CombinationDtos.StepInput(null, whiteId, 1, null, "Dip", null));
        UUID comboId = recipes.create(new CombinationDtos.NewCombination("Layered", null, layers, null, null, null,
                null, null, null, null, null, null, null), owner)
                .id();
        var listed = recipes.search(owner);
        assertEquals(1, listed.size());
        assertEquals(List.of(1, 2), listed.getFirst().steps().stream()
                .map(CombinationDtos.StepView::position).toList());

        Combination combination = combinations.findById(comboId).orElseThrow();
        Club club = clubs.findById(clubId).orElseThrow();
        images.save(ImageRecord.ofCombination(combination, ownerUser, null, ImageVisibility.PRIVATE, ImageKind.TEST_TILE, "private.jpg", 24, 24));
        images.save(ImageRecord.ofCombination(combination, ownerUser, club, ImageVisibility.CLUB, ImageKind.TEST_TILE, "club.jpg", 24, 24));
        images.save(ImageRecord.ofCombination(combination, ownerUser, null, ImageVisibility.PUBLIC, ImageKind.TEST_TILE, "public.jpg", 24, 24));
        assertEquals(1, recipes.search(null).getFirst().images().size());
        assertEquals(2, recipes.search(member).getFirst().images().size());
        assertEquals(3, recipes.search(owner).getFirst().images().size());
    }

    @Test void entitiesAreEqualByIdAcrossInstancesAndProxies() {
        AppUser saved = users.save(new AppUser("equal@example.test", "secret-hash", "Equal"));
        UUID clubId = clubs.save(new Club("Kiln", saved)).getId();
        entities.flush();
        entities.clear();

        AppUser proxy = entities.getReference(AppUser.class, saved.getId());
        assertTrue(proxy instanceof HibernateProxy);
        assertEquals(saved, proxy);
        assertEquals(proxy, saved);
        assertEquals(saved.hashCode(), proxy.hashCode());
        assertEquals(saved, users.findById(saved.getId()).orElseThrow());
        assertNotEquals(saved, new AppUser("equal@example.test", "secret-hash", "Equal"));
        assertNotEquals(saved, clubs.findById(clubId).orElseThrow());
        assertFalse(saved.toString().contains("secret-hash"));
        assertTrue(saved.toString().contains("equal@example.test"));
    }
}
