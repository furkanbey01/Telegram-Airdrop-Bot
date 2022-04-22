import sqlite3,logging,time,random,os
from telegram import Update
from telegram.ext import Updater, CommandHandler, MessageHandler, Filters, CallbackContext

def islem(*args):
    def create_db(name="data.db"):
        if os.path.exists(name):
            return "database zaten var."
        else:
            conn = sqlite3.connect(name)
            c = conn.cursor()
            c.execute("CREATE TABLE IF NOT EXISTS accounts(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, balance INTEGER)")
            conn.commit()
            conn.close()
            return "{} isminde database olusturuldu.".format(str(name))
        
    def add_account(id,name,balance):
        #add account if not exists
        conn = sqlite3.connect('bank.db')
        c = conn.cursor()
        c.execute("INSERT INTO accounts(id,name,balance) VALUES(?,?,?)",(id,name,balance))
        conn.commit()
        conn.close()
        return "hesap oluşturuldu:{} {} {}".format(id,name,balance)

    def edit_account(id,balance):
        conn = sqlite3.connect('bank.db')
        c = conn.cursor()
        c.execute("UPDATE accounts SET balance = ? WHERE id = ?",(balance,id))
        conn.commit()
        conn.close()
        return "hesap güncellendi: {} {}".format(id,balance)

    def delete_account(id):
        conn = sqlite3.connect('bank.db')
        c = conn.cursor()
        c.execute("DELETE FROM accounts WHERE id = ?",(id,))
        conn.commit()
        conn.close()
        return "hesap silindi:"+str(id)

    def show_accounts():
        conn = sqlite3.connect('bank.db')
        c = conn.cursor()
        c.execute("SELECT * FROM accounts")
        accounts = c.fetchall()
        conn.close()
        return accounts

    def search_account(id):
        conn = sqlite3.connect('bank.db')
        c = conn.cursor()
        c.execute("SELECT * FROM accounts WHERE id = ?",(id,))
        account = c.fetchone()
        conn.close()
        return account

    def show_balance(id):
        conn = sqlite3.connect('bank.db')
        c = conn.cursor()
        c.execute("SELECT balance FROM accounts WHERE id = ?",(id,))
        balance = c.fetchone()
        conn.close()
        text=""
        for i in balance:
            text+=str(i)+"\n"
        return text

    def transfer(id1,id2,n): #kimden,kime,miktar
        if int(n) > int(show_balance(id1)):
            return "Yetersiz bakiye. \n{} kadar eksik var.".format(int(n)-int(show_balance(id1)))
        else:
            edit_account(id1,int(show_balance(id1))-int(n))
            edit_account(id2,int(show_balance(id2))+int(n))
            return "Transfer başarılı: {}'den {}'e \n{} kadar.".format(id1,id2,n)

    if args[0] == "add":
        add_account(args[1],args[2],args[3])
        print("hesap eklendi: {} {} {}".format(args[1],args[2],args[3]))
    elif args[0] == "edit":
        edit_account(args[1],args[2])
        print("hesap güncellendi: {} {}".format(args[1],args[2]))
    elif args[0] == "delete":
        delete_account(args[1])
        print("hesap silindi: {}".format(args[1]))
    elif args[0] == "show":
        text=""
        for i in show_accounts():
            text+=str(i)+"\n"
        return text
    elif args[0] == "search":
        text=""
        for i in search_account(args[1]):
            text+=str(i)+"\n"
        return text
    elif args[0] == "balance":
        return show_balance(args[1])
    elif args[0] == "transfer":
        return transfer(args[1],args[2],args[3])
    elif args[0] == "create":
        return print(create_db())
    else:
        return "Hatalı komut."


#       Bot Token ve Ayarlar Kısmı
token = 'BOT TOKEN HERE'
logging.basicConfig(format='%(asctime)s - %(name)s - %(levelname)s - %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

def start(update: Update, context: CallbackContext) -> None:
    update.message.reply_text('Merhaba!\nstart komutu ile botumuzu başlattınız.')

def help_command(update: Update, context: CallbackContext) -> None:
    update.message.reply_text("help komutu ile yardım alabilirsiniz.")

def echo(update: Update, context: CallbackContext) -> None:
    update.message.reply_text(update.message.text)
    update.message.reply_text(islem(update.message.text))

    

def main() -> None:
    updater = Updater(token)
    dispatcher = updater.dispatcher
    dispatcher.add_handler(CommandHandler("start", start))
    dispatcher.add_handler(CommandHandler("help", help_command))
    dispatcher.add_handler(MessageHandler(Filters.text & ~Filters.command, echo))

    updater.start_polling()
    updater.idle()

if __name__ == '__main__':
    main()
