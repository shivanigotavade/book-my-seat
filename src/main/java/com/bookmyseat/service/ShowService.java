package com.bookmyseat.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bookmyseat.entity.ShowEntity;
import com.bookmyseat.repository.SeatRepository;
import com.bookmyseat.repository.ShowRepository;
import com.bookmyseat.domain.CreateShowRequest;
import com.bookmyseat.domain.ShowDetailResponse;
import com.bookmyseat.validator.ShowRequestValidator;
import com.bookmyseat.domain.ShowResponse;
import com.bookmyseat.exception.handler.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Show creation (G4) and state (G10) backed by JPA repositories. Contested
 * paths stay explicit: bulk seat creation is a single native round trip, and
 * state reads share one {@code REPEATABLE READ} snapshot.
 */
@Service
public class ShowService {

	private final ShowRepository shows;
	private final SeatRepository seats;
	private final TransactionTemplate readSnapshot;
	private final ObjectMapper json;

	public ShowService(ShowRepository shows, SeatRepository seats, PlatformTransactionManager txManager,
			ObjectMapper json) {
		this.shows = shows;
		this.seats = seats;
		this.readSnapshot = new TransactionTemplate(txManager);
		this.readSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		this.readSnapshot.setReadOnly(true);
		this.json = json;
	}

	@Transactional
	public ShowResponse createShow(CreateShowRequest request) {
		var validated = ShowRequestValidator.validate(request == null ? null : request.name(),
				request == null ? null : request.seats(), request == null ? null : request.pricePaise(),
				request == null ? null : request.perUserLimit());

		UUID showId = UUID.randomUUID();
		shows.save(new ShowEntity(showId, validated.name(), validated.pricePaise(), validated.perUserLimit(),
				validated.labels().size()));

		List<String> labels = validated.labels();
		try {
			seats.bulkInsert(showId, json.writeValueAsString(labels));
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException("Seat labels are not JSON serialisable", e);
		}

		List<ShowResponse.SeatItem> items = labels.stream()
				.map(label -> new ShowResponse.SeatItem(label, "available")).toList();
		return new ShowResponse(showId, validated.name(), validated.pricePaise(), validated.perUserLimit(),
				labels.size(), items);
	}

	/**
	 * Show state with the reconciliation invariant (G10). Header, counts and
	 * seat list are read in one {@code REPEATABLE READ} read-only transaction
	 * so they describe the same snapshot — polling mid-burst never observes a
	 * sum different from {@code total_seats}.
	 */
	public ShowDetailResponse getShow(UUID showId, boolean summary) {
		return readSnapshot.execute(status -> {
			ShowEntity show = shows.findById(showId)
					.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown show."));
			Map<String, Long> counts = new HashMap<>(Map.of("available", 0L, "held", 0L, "confirmed", 0L));
			for (Object[] row : seats.countByStatus(showId)) {
				counts.put((String) row[0], (Long) row[1]);
			}
			List<ShowResponse.SeatItem> items = summary ? null
					: seats.findByShowIdOrderBySeatLabelAsc(showId).stream()
							.map(s -> new ShowResponse.SeatItem(s.getSeatLabel(), s.getStatus())).toList();
			return new ShowDetailResponse(showId, show.getName(), show.getPricePaise(), show.getPerUserLimit(),
					show.getTotalSeats(), new ShowDetailResponse.Counts(counts.get("available"),
							counts.get("held"), counts.get("confirmed")),
					items);
		});
	}
}
