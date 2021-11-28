package dev.publio.telegrampackagenotifier.telegram;

import static dev.publio.telegrampackagenotifier.telegram.MessageBuilderTelegram.buildCompanyButtonText;
import static dev.publio.telegrampackagenotifier.telegram.MessageBuilderTelegram.buildPackageInfoMessage;
import static dev.publio.telegrampackagenotifier.telegram.MessageBuilderTelegram.buildTrackingUpdateMessage;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.model.request.KeyboardButton;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.model.request.ReplyKeyboardMarkup;
import com.pengrad.telegrambot.request.SendMessage;
import dev.publio.telegrampackagenotifier.exceptions.NoPackagesFoundException;
import dev.publio.telegrampackagenotifier.exceptions.UserHasActionsException;
import dev.publio.telegrampackagenotifier.exceptions.UserNotActiveException;
import dev.publio.telegrampackagenotifier.models.Package;
import dev.publio.telegrampackagenotifier.models.ShippingUpdate;
import dev.publio.telegrampackagenotifier.models.User;
import dev.publio.telegrampackagenotifier.models.enums.ActionsType;
import dev.publio.telegrampackagenotifier.models.enums.ActionsValues;
import dev.publio.telegrampackagenotifier.service.TrackingService;
import dev.publio.telegrampackagenotifier.service.UserChatActionsService;
import dev.publio.telegrampackagenotifier.service.UserService;
import dev.publio.telegrampackagenotifier.shipping.companies.ShippingCompanies;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Log4j2
@Component
public class ProcessMessageTelegram {

  public static final String MY_PACKAGES = "📦 Meus pacotes";
  public static final String ADD_PACKAGE = "🆕 Adicionar pacote";
  public static final String START = "/start";
  public static final String YOUR_PACKAGES = "⬇️⬇️⬇️⬇️ Pacotes ⬇️⬇️⬇️⬇️";
  public static final String CHOOSE_TRANSPORTER = "Selecione a transportadora:";

  private final TelegramBot telegramBot;
  private final UserService userService;
  private final TrackingService trackingService;
  private final UserChatActionsService userChatActionsService;

  public ProcessMessageTelegram(
      TelegramBot telegramBot,
      UserService userService,
      TrackingService trackingService,
      UserChatActionsService userChatActionsService) {
    this.telegramBot = telegramBot;
    this.userService = userService;
    this.trackingService = trackingService;
    this.userChatActionsService = userChatActionsService;
  }

  public void processCallback(Update update) {
    log.info("Processing callback");
    final List<SendMessage> messageList = new ArrayList<>();
    final Long requestChatId = update.callbackQuery().from().id();
    final String requestMessage = update.callbackQuery().message().text();
    final String requestData = update.callbackQuery().data();

    try {
      final var user = userService.findUserByChatId(requestChatId.toString());

      switch (requestMessage) {
        default:
          throw new Exception("Invalid message");
        case YOUR_PACKAGES: {
          Set<ShippingUpdate> updates = trackingService.getPackage(requestData)
              .getUpdates();
          updates.forEach(shippingUpdate -> messageList.add(
              new SendMessage(requestChatId, buildTrackingUpdateMessage(shippingUpdate)).parseMode(
                  ParseMode.Markdown))
          );
          break;
        }
        case CHOOSE_TRANSPORTER: {
          userChatActionsService.updateAction(user.getId(), ActionsType.NEW_PACKAGE, Map.of(
              ActionsValues.TRANSPORTER, requestData));
          messageList.add(
              new SendMessage(requestChatId, "Digite o número do pacote").allowSendingWithoutReply(
                  false));
          break;
        }
      }
    } catch (NoPackagesFoundException e) {
      log.info("No updates found");
      messageList.clear();
      messageList.add(new SendMessage(requestChatId, "Pacote sem atualizações"));
    } catch (Exception e) {
      log.error("Error processing message: " + requestMessage);
      messageList.clear();
      messageList.add(new SendMessage(requestChatId, "Error inesperado"));
      messageList.add(new SendMessage(requestChatId, "Tente novamente mais tarde"));
    } finally {
      messageList.forEach(telegramBot::execute);

      log.info("Callback processed");
    }
  }

  public void processMessage(Update update) {
    List<SendMessage> messageList = new ArrayList<>();
    Message requestMessage = update.message();
    Long requestChatId = requestMessage.from().id();
    log.info("Received message from: " + requestMessage.from().firstName());
    log.info("Received message: " + requestMessage.text());
    try {
      User requestUser = userService.createUserIfNotExists(requestChatId,
          requestMessage.from().firstName(),
          requestMessage.from().username());

      validateIfUserIsActive(requestUser);

      validateIfUserHasActions(requestUser);

      switch (requestMessage.text()) {
        default:
        case START:
          ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup(
              new KeyboardButton(ADD_PACKAGE),
              new KeyboardButton(MY_PACKAGES))
              .resizeKeyboard(true);
          messageList.add(
              new SendMessage(requestChatId, "Selecione uma das opções abaixo para começar:")
                  .replyMarkup(keyboardMarkup));
          break;
        case MY_PACKAGES:
          returnUserActivePackages(messageList, requestChatId, requestUser);
          break;
        case ADD_PACKAGE:
          final var inlineKeyboardMarkup = new InlineKeyboardMarkup();
          for (ShippingCompanies shippingCompany : ShippingCompanies.values()) {
            inlineKeyboardMarkup.addRow(
                new InlineKeyboardButton(buildCompanyButtonText(shippingCompany)).callbackData(
                    shippingCompany.toString()));
          }
          messageList.add(
              new SendMessage(requestChatId, CHOOSE_TRANSPORTER).replyMarkup(inlineKeyboardMarkup)
                  .parseMode(ParseMode.Markdown));
      }
    } catch (UserHasActionsException e) {
      final var userChatActions = e.getUserChatActions();
      switch (userChatActions.action()) {
        case NEW_PACKAGE -> {
          final var companies = ShippingCompanies.valueOf(
              userChatActions.values().get(ActionsValues.TRANSPORTER));
          trackingService.createPackage(requestMessage.text(), companies,
              userChatActions.userId());
          messageList.add(new SendMessage(requestChatId, "Pacote adicionado com sucesso 🎉"));
        }
        default -> messageList.add(new SendMessage(requestChatId, "Opção inválida"));
      }
      userChatActionsService.deleteAction(userChatActions.userId());
    } catch (NoPackagesFoundException e) {
      log.error("No packages found for user: " + requestChatId);
      messageList.clear();
      messageList.add(new SendMessage(requestChatId, "Você não possui pacotes cadastrados."));
    } catch (UserNotActiveException e) {
      log.error("User not active: " + requestChatId);
      messageList.clear();
      messageList.add(new SendMessage(requestChatId, "❌ Infelizmente seu usuário não está ativo."));
    } catch (Exception e) {
      log.error("Error processing message: " + requestMessage.text());
      messageList.clear();
      messageList.add(new SendMessage(requestChatId, "Error inesperado."));
      messageList.add(new SendMessage(requestChatId, "Tente novamente mais tarde."));
    } finally {
      messageList.forEach(telegramBot::execute);
      log.info("Message sent to: " + requestChatId);
    }
  }

  private void validateIfUserHasActions(User requestUser) {
    final var action = userChatActionsService.getAction(requestUser.getId());
    if (action.isPresent()) {
      throw new UserHasActionsException(action.get());
    }
  }

  private void validateIfUserIsActive(User currentUser)
      throws UserNotActiveException {
    if (!currentUser.isActive()) {
      throw new UserNotActiveException(currentUser.getId());
    }
  }

  private void returnUserActivePackages(List<SendMessage> messageList, Long id, User currentUser)
      throws NoPackagesFoundException {
    Set<Package> allActivePackagesByUser = trackingService.getAllActivePackagesByUser(
        currentUser.getId());
    InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
    if (allActivePackagesByUser.isEmpty()) {
      throw new NoPackagesFoundException(currentUser.getId());
    }

    for (Package aPackage : allActivePackagesByUser) {
      inlineKeyboardMarkup.addRow(
          new InlineKeyboardButton(buildPackageInfoMessage(aPackage)).callbackData(
              aPackage.getTrackId()));
    }
    messageList.add(new SendMessage(id, YOUR_PACKAGES).replyMarkup(inlineKeyboardMarkup)
        .parseMode(ParseMode.MarkdownV2));
  }
}
