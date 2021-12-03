package dev.publio.telegrampackagenotifier.shipping.companies.correios;

import dev.publio.telegrampackagenotifier.dto.ShippingUpdateDTO;
import dev.publio.telegrampackagenotifier.exceptions.UnableToGetShippingUpdateException;
import dev.publio.telegrampackagenotifier.shipping.companies.ShippingCompanies;
import dev.publio.telegrampackagenotifier.shipping.factory.ShippingCompanyTracker;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Log4j2
public class CorreiosTracker implements ShippingCompanyTracker {

  @Value("${transporter.urls.correios}")
  private String correiosUrl;

  @Override
  public ShippingCompanies getCompanyName() {
    return ShippingCompanies.CORREIOS;
  }

  @Override
  public Set<ShippingUpdateDTO> getShippingUpdate(String trackId) {
    log.info("Getting correios shipping updates for {}", trackId);
    try {
      return Jsoup.connect(mountUrlWithTrackId(trackId))
          .followRedirects(true)
          .timeout(10000)
          .execute()
          .parse()
          .getElementsByClass("linha_status").stream()
          .filter(x -> !x.select("[style*=border-bottom: 0;]").hasText())
          .map(x -> x.select("li").eachText())
          .filter(x -> x.size() >= 3)
          .map(x -> x.stream().map(y -> y.substring(y.indexOf(":") + 2))
              .collect(Collectors.toList()))
          .map(x -> x.stream().map(y -> y.replaceFirst("Agência dos Correios - ", ""))
              .collect(Collectors.toList()))
          .map(x -> x.stream().map(y -> y.replaceFirst(" - por favor aguarde", ""))
              .collect(Collectors.toList()))
          .map(x -> x.stream().map(y -> y.replaceFirst("\\| Hora: ", ""))
              .collect(Collectors.toList()))
          .map(x -> new ShippingUpdateDTO(
              LocalDateTime.parse(x.get(1), DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
              x.get(2),
              x.get(0), ShippingCompanies.CORREIOS))
          .collect(Collectors.toSet());
    } catch (Exception e) {
      log.error("Unable to get correios shipping updates for {}", trackId, e);
      throw new UnableToGetShippingUpdateException(e.getMessage());
    }
  }

  private String mountUrlWithTrackId(String trackId) {
    log.info("Mounting correios url with track id {}", trackId);
    return String.format(correiosUrl, trackId);
  }

}
