from pathlib import Path
source=Path(__file__).with_name('quest_editor_read.py').read_text()
needle="        assert not re.search(r'\\bAppQuest\\b',s)\n"
replacement='''        old_fixture = """                var quest = new logbook.bean.QuestList.Quest();
                quest.setNo(905); quest.setCategory(2); quest.setType(4); quest.setTitle(\"fixture\");
                var appQuest = new AppQuest(); appQuest.setNo(905); appQuest.setQuest(quest);"""
        if old_fixture in s:
            s=once(s,old_fixture,'                var appQuest = new Choice(905,2,4,"fixture");')
        assert not re.search(r'\\bAppQuest\\b',s)
'''
assert source.count(needle)==1
source=source.replace(needle,replacement)
exec(compile(source,'quest_editor_read_fixture_corrected','exec'),globals())
