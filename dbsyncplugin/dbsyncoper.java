import java.util.*;
import java.util.regex.*;
import java.io.FileNotFoundException;
import com.esotericsoftware.wildcard.Paths;

class DBSyncOper
{
	public enum Scope { SCOPE_GLOBAL, SCOPE_SOURCE, SCOPE_DESTINATION };

	class SortTable implements Reader
	{
		private String tablename;
		private String conn;
		private DB.DBOper oper;
		private TreeMap<String,LinkedHashMap<String,Set<String>>> sortedmap;
		private Iterator<?> iterator;
		private String instance;
		private ArrayList<String> header;

		public SortTable(XML xml,Sync sync) throws Exception
		{
			Reader reader = sync.getReader();
			this.header = reader.getHeader();

			XML sortxml = xml.getElement("dbsyncsorttable");
			if (sortxml == null)
			{
				xml = xml.getParent();
				sortxml = xml.getElement("dbsyncsorttable");
			}
			if (sortxml == null)
				sortxml = xml.getParent().getElement("dbsyncsorttable");
			if (sortxml != null)
			{
				tablename = sortxml.getAttribute("name");
				conn = sortxml.getAttribute("instance");
			}

			if (tablename == null || conn == null)
			{
				instance = "memsort/" + reader.getName();
				sortedmap = new TreeMap<String,LinkedHashMap<String,Set<String>>>(db.collator);
				Misc.log(7,"Initializing memory sort");
			}
			else
			{
				instance = "dbsort/" + reader.getName();
				Misc.log(7,"Initializing temporary DB table sort");
			}

			put(sync);
		}

		public void put(Sync sync) throws Exception
		{
			LinkedHashMap<String,String> row;
			while((row = fields.getNext(sync)) != null)
			{
				if (!row.keySet().containsAll(fields.getKeys()))
					throw new AdapterException("Sort operation requires all keys [" + Misc.implode(fields.getKeys()) + "]: " + Misc.implode(row));
				put(row);
			}
		}

		public void put(LinkedHashMap<String,String> row) throws Exception
		{
			String key = getKey(row);
			if (key.length() == fields.getKeys().size()) return; // An empty key contains one ! per element

			if (ignorecasekeys) key = key.toUpperCase();
			if (sortedmap != null)
			{
				LinkedHashMap<String,Set<String>> prevmap = sortedmap.get(key);
				if (prevmap == null)
				{
					prevmap = new LinkedHashMap<String,Set<String>>();
					sortedmap.put(key,prevmap);
				}
				ReaderUtil.pushCurrent(row,prevmap,false);
				return;
			}

			XML xml = new XML();
			xml.add("row",row);

			String value = xml.rootToString();
			String sql = "insert into " + tablename + " values (" + DB.replacement + "," + DB.replacement + ")";

			ArrayList<String> list = new ArrayList<String>();
			list.add(key);
			list.add(value);

			db.execsql(conn,sql,list);
		}

		@Override
		public LinkedHashMap<String,String> nextRaw() throws Exception
		{
			throw new AdapterException("nextRaw not supported for SortTable class");
		}

		@Override
		public LinkedHashMap<String,String> next() throws Exception
		{
			LinkedHashMap<String,String> row = new LinkedHashMap<String,String>();

			if (sortedmap != null)
			{
				if (iterator == null)
					iterator = sortedmap.keySet().iterator();
				if (!iterator.hasNext()) return null;
				Object key = iterator.next();
				LinkedHashMap<String,Set<String>> map = sortedmap.get(key);
				for(String keyrow:map.keySet())
					row.put(keyrow,Misc.implode(map.get(keyrow),"\n"));

				if (Misc.isLog(15)) Misc.log("row [" + instance + "]: " + row);
				return row;
			}

			if (oper == null)
			{
				String sql = "select key,value from " + tablename + db.getOrderBy(conn,new String[]{"key"},ignorecasekeys);
				oper = db.makesqloper(conn,sql);
			}

			LinkedHashMap<String,String> result = oper.next();
			if (result == null)
			{
				db.execsql(conn,"truncate table " + tablename);
				return null;
			}

			XML xml = new XML(new StringBuffer(result.get("VALUE")));
			XML[] elements = xml.getElements(null);

			for(XML el:elements)
			{
				String value = el.getValue();
				if (value == null) value = "";
				row.put(el.getTagName(),value);
			}

			if (Misc.isLog(15)) Misc.log("row [" + instance + "]: " + row);
			return row;
		}

		public ArrayList<String> getHeader()
		{
			return header;
		}

		public String getName()
		{
			return instance;
		}
	}

	class Sync
	{
		private XML xml;
		protected Reader reader;
		private CsvWriter csvout;
		private String syncname;
		private boolean dumplogfile = false;

		public Sync(XML xml) throws Exception
		{
			this.xml = xml;

			String dumpcsvfilename = xml.getAttribute("dumpcsvfilename");
			if (dumpcsvfilename == null && dbsyncplugin.dumpcsv_mode)
				dumpcsvfilename = javaadapter.getName() + "_" + dbsyncname + "_" + getName() + "_" + Misc.implode(fields.getKeys(),"_") + ".csv";

			if (dumpcsvfilename != null) csvout = new CsvWriter(dumpcsvfilename,xml);

			String dumplogfilestr = xml.getAttribute("dumplogfile");
			if (dumplogfilestr != null && dumplogfilestr.equals("true"))
				dumplogfile = true;

			// Important: reader must be set by extended constructor class
		}

		public LinkedHashMap<String,String> next() throws Exception
		{
			return reader.next();
		}

		public ArrayList<String> getHeader()
		{
			return reader.getHeader();
		}

		public XML getXML()
		{
			return xml;
		}

		public Reader getReader()
		{
			return reader;
		}

		public String getName() throws Exception
		{
			if (syncname != null) return syncname;
			syncname = xml.getAttribute("name");
			if (syncname == null) syncname = xml.getAttribute("instance");
			if (syncname == null) syncname = xml.getAttribute("filename");
			if (syncname == null) syncname = xml.getTagName();
			return syncname;
		}

		public void makeDump(LinkedHashMap<String,String> result) throws Exception
		{
			if (csvout != null) csvout.write(result);
			if (dumplogfile) Misc.log(Misc.implode(result));
		}

		public void closeDump() throws Exception
		{
			if (csvout != null) csvout.flush();
		}
	}

	class SyncClass extends Sync
	{
		public SyncClass(XML xml) throws Exception
		{
			super(xml);

			reader = ReaderUtil.getReader(xml);

			String sorted = xml.getAttribute("sorted");
			if (sorted != null && sorted.equals("true")) return;

			reader = new SortTable(xml,this);
		}
	}

	class SyncCsv extends Sync
	{
		public SyncCsv(XML xml) throws Exception
		{
			super(xml);

			reader = new ReaderCSV(xml);

			String sorted = xml.getAttribute("sorted");
			if (sorted != null && sorted.equals("true")) return;

			reader = new SortTable(xml,this);
		}
	}

	class SyncSql extends Sync
	{
		private String conn;

		public SyncSql(XML xml) throws Exception
		{
			super(xml);

			conn = xml.getAttribute("instance");
			if (conn == null)
				throw new AdapterException(xml,"Attribute 'instance' required for database syncer");

			XML sqlxml = xml.getElement("extractsql");
			String sql = sqlxml == null ? xml.getValue() : sqlxml.getValue();
			if (sql == null) sql = "";

			String restrictsql = xml.getValue("restrictsql",null);
			if (restrictsql != null)
			{
				if (restrictsql.indexOf("%SQL%") != -1)
					sql = restrictsql.replace("%SQL%",sql);
				else
					sql = "select * from (" + sql + ") d3 where " + restrictsql;
			}

			sql = sql.replace("%LASTDATE%",lastdate == null ? "" : Misc.dateformat.format(lastdate));
			sql = sql.replace("%STARTDATE%",startdate == null ? "" : Misc.dateformat.format(startdate));
			sql = sql.replace("%NAME%",dbsyncname);

			String filtersql = xml.getAttribute("filter");
			if (filtersql != null)
			{
				if (filtersql.indexOf("%SQL%") != -1)
					sql = filtersql.replace("%SQL%",sql);
				else
					sql = "select * from (" + sql + ") d2 where " + filtersql;
			}

			String sorted = xml.getAttribute("sorted");
			boolean issorted = sorted != null && sorted.equals("true");
			if (!issorted)
			{
				sql = "select * from (" + sql + ") d1";
				Set<String> keys = fields.getKeys();
				sql += db.getOrderBy(conn,keys.toArray(new String[keys.size()]),ignorecasekeys);
			}


			String presql = xml.getValue("preextractsql",null);
			if (presql != null)
				db.execsql(conn,presql);

			// Overwrite default reader
			reader = new ReaderSQL(conn,sql,fields.getKeys(),issorted);
		}
	}

	class SyncSoap extends Sync
	{
		private XML xml;

		public SyncSoap(XML xml) throws Exception
		{
			super(xml);

			XML request = xml.getElement("element");
			XML function = xml.getElement("function");
			if (request == null || function == null)
				throw new AdapterException(xml,"Invalid sync call");

			Subscriber sub = new Subscriber(function);
			XML result = sub.run(request.getElement(null).copy());
			reader = new ReaderXML(xml,result);

			reader = new SortTable(xml,this);
		}
	}

	class SyncXML extends Sync
	{
		private XML xml;

		private void Read(XML xml,XML xmlsource) throws Exception
		{
			Reader reader = ReaderUtil.getReader(xml);

			LinkedHashMap<String,String> row;

			XML xmltable = xmlsource.add(reader.getName());

			while((row = reader.next()) != null)
				xmltable.add("row",row);
		}

		public SyncXML(XML xml,XML xmlsource) throws Exception
		{
			super(xml);

			String name = xml.getAttribute("name");
			String filename = xml.getAttribute("filename");

			if (filename == null)
			{
				if (xmlsource == null)
				{
					xmlsource = new XML();
					xmlsource.add(name == null ? "root" : name);
				}

				XML[] elements = xml.getElements(null);
				for(XML element:elements)
				{
					String tagname = element.getTagName();

					if (tagname.equals("element"))
						xmlsource.add(element);
					else if (tagname.equals("function"))
					{
						Subscriber sub = new Subscriber(element);

						Operation.ResultTypes resulttype = Operation.getResultType(element);
						XML xmlresult = sub.run(xmlsource);
						switch(resulttype)
						{
						case LAST:
							xmlsource = xmlresult;
							break;
						case MERGE:
							xmlsource.add(xmlresult);
							break;
						}
					}
					else if (tagname.equals("read"))
						Read(element,xmlsource);

					if (Misc.isLog(9)) Misc.log("XML Sync " + tagname + ": " +xmlsource);
				}
			}
			else
				xmlsource = new XML(filename);

			reader = new ReaderXML(xml,xmlsource);

			String sorted = xml.getAttribute("sorted");
			if (sorted != null && sorted.equals("true")) return;

			reader = new SortTable(xml,this);
		}
	}

	class Field
	{
		private XML xmlfield;
		private SyncLookup lookup;
		private String typeoverride;
		private String name;
		private String newname;
		private boolean dostrip = false;
		private String copyname;
		private String filtername;
		private String filterresult;
		private boolean iskey = false;

		private String onempty;
		private String forceemptyvalue;
		private String onmultiple;
		private boolean onmultiple_present = false;
		private String ifexists;
		private String synclist[];
		private Scope scope;

		public Field(XML xml,Scope scope) throws Exception
		{
			xmlfield = xml;
			this.scope = scope;
			name = xml.getAttribute("name");
			if (Misc.isLog(10)) Misc.log("Initializing field " + name);
			newname = xml.getAttribute("rename");
			String strip = xml.getAttributeDeprecated("strip");
			if (strip != null && strip.equals("true")) dostrip = true;
			copyname = xml.getAttribute("copy");
			filtername = xml.getAttribute("filter");
			filterresult = xml.getAttribute("filter_result");
			onempty = xml.getAttribute("on_empty");
			if (onempty == null)
			{
				onempty = xml.getAttributeDeprecated("on_not_found");
				if ("".equals(onempty)) onempty = null;
			}
			forceemptyvalue = xml.getAttribute("force_empty_value");
			onmultiple = xml.getAttribute("on_multiple");
			onmultiple_present = xml.isAttributeNoDefault("on_multiple");
			ifexists = null;
			if (xml.isAttribute("if_exists"))
			{
				ifexists = xml.getAttribute("if_exists");
				if (ifexists == null) ifexists = "";
			}
			String forsync = xml.getAttribute("for_sync");
			if (forsync != null) synclist = forsync.split("\\s*,\\s*");

			typeoverride = xml.getAttribute("type");

			lookup = new SyncLookup(xml,this);
		}

		public Field(String name,boolean iskey) throws Exception
		{
			this.iskey = iskey;
			this.name = name;
			lookup = new SyncLookup(this);
		}

		public XML getXML()
		{
			return xmlfield;
		}

		public String getName()
		{
			return name;
		}

		public String getNewName()
		{
			return newname;
		}

		public boolean isStrip()
		{
			return dostrip;
		}

		public String getCopyName()
		{
			return copyname;
		}

		public String getType()
		{
			if (typeoverride != null) return typeoverride;
			if (iskey) return "key";
			return null;
		}

		public boolean isForceEmpty(String value)
		{
			return (forceemptyvalue != null && value.equals(forceemptyvalue));
		}

		public SyncLookup getLookup()
		{
			return lookup;
		}

		public boolean isKey()
		{
			return iskey;
		}

		public boolean isValid(Sync sync) throws Exception
		{
			boolean result = isValidSync(synclist,sync);
			if (!result) return false;
			if (scope == Scope.SCOPE_SOURCE && !sync.getXML().getTagName().equals("source")) return false;
			if (scope == Scope.SCOPE_DESTINATION && !sync.getXML().getTagName().equals("destination")) return false;
			return true;
		}

		public boolean isValidFilter(LinkedHashMap<String,String> result,String name) throws Exception
		{
			if (ifexists != null)
			{
				if (ifexists.isEmpty()) ifexists = name;
				String[] keys = ifexists.split("\\s*,\\s*");
				for(String key:keys)
				{
					String value = result.get(key);
					if (value == null || value.isEmpty()) return false;
				}
			}
			if (filtername == null && filterresult == null) return true;

			XML xml = new XML();
			xml.add("root",result);
			if (Misc.isLog(30)) Misc.log("Looking for filter " + filtername + " [" + filterresult + "]: " + xml);

			return Misc.isFilterPass(xmlfield,xml);
		}
	}

	class Fields
	{
		private LinkedHashSet<String> keyfields;
		private LinkedHashSet<String> namefields;
		private ArrayList<Field> fields;
		private boolean default_set = false;

		public Fields(String[] keyfields) throws Exception
		{
			this.keyfields = new LinkedHashSet<String>(Arrays.asList(keyfields));
			this.namefields = new LinkedHashSet<String>(Arrays.asList(keyfields));
			fields = new ArrayList<Field>();
			for(String keyfield:keyfields)
				fields.add(new Field(keyfield,true));
		}

		private void setDefaultFields(LinkedHashMap<String,String> result) throws Exception
		{
			// To be done once
			if (default_set) return;
			default_set = true;
			XML xml = new XML();
			xml = xml.add("field");
			for(String name:result.keySet())
			{
				if (namefields.contains(name)) continue;
				xml.setAttribute("name",name);
				fields.add(new Field(xml,Scope.SCOPE_GLOBAL));
			}
		}

		public void addDefaultVar(XML xml) throws Exception
		{
			XML.setDefaultVariable(xml.getAttribute("name"),xml.getAttribute("value"));
		}

		public void removeDefaultVar(XML xml) throws Exception
		{
			XML.setDefaultVariable(xml.getAttribute("name"),null);
		}

		public void add(XML xml,Scope scope) throws Exception
		{
			namefields.add(xml.getAttribute("name"));
			Field field = new Field(xml,scope);

			String default_onmultiple = null;
			for(Field prevfield:fields)
				if (prevfield.getName().equals(field.getName()) && prevfield.scope == Scope.SCOPE_GLOBAL && prevfield.onmultiple_present)
				{
					default_onmultiple = prevfield.onmultiple;
					break;
				}
			if (!field.onmultiple_present && default_onmultiple != null)
				field.onmultiple = default_onmultiple;

			fields.add(field);
		}

		public Set<String> getNames()
		{
			return namefields;
		}

		public Set<String> getKeys()
		{
			return keyfields;
		}

		public ArrayList<Field> getFields()
		{
			return fields;
		}

		private void doFunction(LinkedHashMap<String,String> result,XML function) throws Exception
		{
			if (function == null) return;
			if (javaadapter.isShuttingDown()) return;

			XML xml = new XML();
			xml.add("root",result);

			if (Misc.isLog(30)) Misc.log("BEFORE FUNCTION: " + xml);

			Subscriber sub = new Subscriber(function);
			XML resultxml = sub.run(xml);

			if (Misc.isLog(30)) Misc.log("AFTER FUNCTION: " + resultxml);

			result.clear();
			XML elements[] = resultxml.getElements();
			for(XML element:elements)
			{
				String value = element.getValue();
				if (value == null)
				{
					if (keyfields.contains(element.getTagName()))
					{
						if (Misc.isLog(30)) Misc.log("Key " + element.getTagName() + " is null!");
						result.clear();
						return;
					}

					value = "";
				}
				result.put(element.getTagName(),value);
			}
		}

		public LinkedHashMap<String,String> getNext(Sync sync) throws Exception
		{
			try {
				return getNextSub(sync);
			} catch(Exception ex) {
				Misc.rethrow(ex,"ERROR: Exception generated while reading " + sync.getName());
			}
			return null;
		}

		public LinkedHashMap<String,String> getNextSub(Sync sync) throws Exception
		{
			LinkedHashMap<String,String> result;

			while((result = sync.next()) != null)
			{
				Set<String> keyset = getKeys();
				String keys = getDisplayKey(keyset,result);

				boolean doprocessing = true;
				if (sync.getReader() instanceof SortTable)
				{
					if (Misc.isLog(30)) Misc.log("Field: Skipping field processing since already done during sort");
					doprocessing = false;
				}

				if (doprocessing) setDefaultFields(result);
				if (doprocessing) fieldloop: for(Field field:fields)
				{
					String name = field.getName();
					boolean iskey = keyset.contains(name);
					String value = result.get(name);
					SyncLookup lookup = field.getLookup();

					if (Misc.isLog(30)) Misc.log("Field: [" + keys + "] Check " + name + ":" + value + ":" + sync.getXML().getTagName() + ":" + (sourcesync == null ? "NOSRC" : sourcesync.getName()) + ":" + (destinationsync == null ? "NODEST" : destinationsync.getName()) + ":" + result);
					if (!field.isValid(sync)) continue;
					if (!field.isValidFilter(result,name)) continue;

					if (Misc.isLog(30)) Misc.log("Field: " + name + " is valid");

					try {
						SyncLookupResultErrorOperationTypes erroroper = lookup.check(result,name);
						switch(erroroper)
						{
						case NONE:
							value = result.get(name);
							break;
						case ERROR:
							Misc.log("ERROR: [" + sync.getName() + ":" + keys + "] Rejecting record since lookup for field " + field.getName() + " failed: " + result);
						case REJECT_RECORD:
							result = null;
							break fieldloop;
						case WARNING:
							Misc.log("WARNING: [" + sync.getName() + ":" + keys + "] Rejecting field " + field.getName() + " since lookup failed: " + result);
						case REJECT_FIELD:
							if (iskey)
								result.put(name,"");
							else
								result.remove(name);
							continue;
						case EXCEPTION:
							throw new AdapterException("[" + sync.getName() + ":" + keys + "] Invalid lookup for field " + field.getName() + ": " + result);
						}
					} catch (AdapterScriptException ex) {
						Misc.log("WARNING: [" + sync.getName() + ":" + keys + "] Script exception on field " + name + ": " + ex.getMessage());
						if (iskey)
							result.put(name,"");
						else
							result.remove(name);
						continue;
					} catch (Exception ex) {
						Misc.rethrow(ex);
					}

					if (value == null) value = "";
					boolean emptyvalueforced = field.isForceEmpty(value);
					if (emptyvalueforced) value = "";

					if (field.isStrip())
					{
						String escape = "[\\s-/.,:;\\|]";
						value = value.replaceAll(escape + "*$","").replaceAll("^" + escape + "*","");
					}

					if (Misc.isLog(30)) Misc.log("Field: " + name + " is finally set to: " + value);

					if (value.contains("\n"))
					{
						String onmultiple = field.onmultiple;
						if (onmultiple == null || onmultiple.equals("ignore"))
							;
						else if (onmultiple.equals("error"))
						{
							String onempty = field.onempty;
							if (onempty != null && (onempty.equals("reject_record") || onempty.equals("error")))
							{
								Misc.log("ERROR: [" + sync.getName() + ":" + keys + "] Rejecting record since field " + field.getName() + " contains multiple values: " + result);
								result = null;
								break;
							}
							Misc.log("ERROR: [" + sync.getName() + ":" + keys + "] Rejecting field " + field.getName() + " since it contains multiple values: " + result);
							if (iskey)
								result.put(name,"");
							else
								result.remove(name);
							continue;
						}
						else if (onmultiple.equals("warning"))
							Misc.log("WARNING: [" + sync.getName() + ":" + keys + "] Field " + field.getName() + " contains multiple values: " + result);
						else if (onmultiple.equals("merge"))
							value = Misc.implode(value.split("\n"),",");
						else
							throw new AdapterException("Invalid on_multiple attribute " + onmultiple + " for field " + field.getName());
					}

					result.put(name,value);

					String copyname = field.getCopyName();
					if (copyname != null)
					{
						if (Misc.isLog(30)) Misc.log("Field: " + name + " copied to " + copyname + ": " + value);
						result.put(copyname,value);
					}

					String newname = field.getNewName();
					if (newname != null)
					{
						if (keyset.contains(newname)) iskey = true;
						value = result.remove(name);
						if (result.containsKey(newname))
							throw new AdapterException("Renaming " + name + " to " + newname + " overwriting an existing field is not supported as it causes unexpected behaviors");
						result.put(newname,value);
						if (Misc.isLog(30)) Misc.log("Field: " + name + " renamed to " + newname + ": " + value);
						name = newname;
					}

					if (emptyvalueforced || !value.isEmpty())
						continue;

					// No value found...
					String onempty = field.onempty;

					if (onempty == null || onempty.equals("ignore"))
						;
					else if (onempty.equals("reject_field"))
					{
						if (Misc.isLog(30)) Misc.log("REJECTED empty field: " + field.getName());
						if (iskey)
							result.put(name,"");
						else
							result.remove(name);
						if (copyname != null) result.remove(copyname);
					}
					else if (onempty.equals("reject_record"))
					{
						if (Misc.isLog(30)) Misc.log("REJECTED record: " + result);
						result = null;
						break;
					}
					else if (onempty.equals("warning"))
					{
						Misc.log("WARNING: [" + sync.getName() + ":" + keys + "] Rejecting field " + field.getName() + " since empty: " + result);
						if (iskey)
							result.put(name,"");
						else
							result.remove(name);
						if (copyname != null) result.remove(copyname);
					}
					else if (onempty.equals("error"))
					{
						Misc.log("ERROR: [" + sync.getName() + ":" + keys + "] Rejecting record since field " + field.getName() + " is empty: " + result);
						result = null;
						break;
					}
					else if (onempty.equals("exception"))
						throw new AdapterException("Field " + field.getName() + " is empty: " + result);
					else
						throw new AdapterException("Invalid on_empty attribute '" + onempty + "' for field " + field.getName());
				}

				if (result == null) continue;

				if (doprocessing)
				{
					XML function = sync.getXML().getElement("element_function");
					String forsync = function == null ? null : function.getAttribute("for_sync");
					if (forsync == null || isValidSync(forsync.split("\\s*,\\s*"),sync))
						doFunction(result,function);

					function = xmlsync.getElement("element_function");
					forsync = function == null ? null : function.getAttribute("for_sync");
					if (forsync == null || isValidSync(forsync.split("\\s*,\\s*"),sync))
						doFunction(result,function);
				}

				if (result.isEmpty())
				{
					if (Misc.isLog(30)) Misc.log("REJECTED because empty");
					continue;
				}

				if (Misc.isLog(30)) Misc.log("PASSED: " + result);
				if (doprocessing) sync.makeDump(result);
				return result;
			}

			sync.closeDump();
			return null;
		}
	}

	class RateCounter
	{
		int total = 0;
		int add = 0;
		int remove = 0;
		int update = 0;
	}

	private int maxqueuelength;
	private static final int DEFAULTMAXQUEUELENGTH = 100;

	private XML xmlsync;

	private String dbsyncname;
	private String rootname;
	private ArrayList<XML> xmloperlist;
	private RateCounter counter;
	private DB db;
	private Date lastdate;
	private Date startdate;
	private boolean tobreak = false;
	private int breakcount = 0;
	private boolean checkcolumn = true;
	private boolean directmode = false;
	private Sync sourcesync;
	private Sync destinationsync;
	private DatabaseUpdateSubscriber update;
	private LinkedHashSet<String> displayfields;
	private Fields fields;
	private Fields fieldssource;
	private Fields fieldsdestination;

	// Global flags
	enum doTypes { TRUE, FALSE, ERROR };
	private doTypes doadd;
	private doTypes doremove;
	private doTypes doupdate;
	private boolean ignorecasekeys;
	private boolean ignorecasefields;

	public DBSyncOper() throws Exception
	{
		db = DB.getInstance();
		update = new DatabaseUpdateSubscriber();
	}

	private String getDisplayKey(Set<String> keys,Map<String,String> map)
	{
		String result = Misc.getKeyValue(keys,map);
		if (result == null) return null;

		if (displayfields == null) return result;

		LinkedHashSet<String> set = new LinkedHashSet<String>(displayfields);
		set.removeAll(keys);
		String displaykeys = Misc.getKeyValue(set,map);
		if (displaykeys == null) return result;

		return displaykeys + "/" + result;
	}

	public boolean isValidSync(String[] synclist,Sync sync) throws Exception
	{
		if (sync == null) return false;
		if (synclist == null) return true;
		if (Misc.isLog(30)) Misc.log("Field: Doing validation against " + Misc.implode(synclist));

		String name = sync.getName();
		if (name == null) return false;
		if (Misc.indexOf(synclist,name) != -1) return true;

		String sourcename = sourcesync == null ? null : sourcesync.getName();
		String destname = destinationsync == null ? null : destinationsync.getName();

		if (sourcename != null && destname != null)
			return Misc.indexOf(synclist,sourcename + "-" + destname) != -1;

		if (Misc.isLog(30)) Misc.log("Field: Validation not found in: " + name + "," + sourcename + "," + destname);
		return false;
	}

	private void flush() throws Exception
	{
		if (dbsyncplugin.preview_mode || directmode || destinationsync == null)
		{
			xmloperlist.clear();
			return;
		}

		breakcount++;
		// Uncomment for debugging: if (breakcount >= 10) tobreak = true;

		XML xml = new XML();

		XML xmlop = xml.add(rootname);
		xmlop.setAttribute("name",dbsyncname);
		if (displayfields != null)
			xmlop.setAttribute("display_keyfield",Misc.implode(displayfields));

		String[] attrs = {"instance","table","type","on_duplicates","merge_fields"};
		for(String attr:attrs)
			xmlop.setAttribute(attr,destinationsync.getXML().getAttribute(attr));

		if ((counter.add + counter.update + counter.remove) > 0)
		{
			for(XML xmloper:xmloperlist)
				xmlop.add(xmloper);

			Publisher publisher = Publisher.getInstance();
			publisher.publish(xml,xmlsync);
		}

		xmloperlist.clear();
	}

	private void push(String oper) throws Exception
	{
		if (destinationsync == null) return;

		XML xml = new XML();
		XML node = xml.add(oper);
		if (oper.equals("end"))
		{
			node.setAttribute("total","" + counter.total);
			node.setAttribute("add","" + counter.add);
			node.setAttribute("remove","" + counter.remove);
			node.setAttribute("update","" + counter.update);
		}

		if (directmode)
		{
			if (!dbsyncplugin.preview_mode && destinationsync != null) update.oper(destinationsync.getXML(),xml);
			return;
		}

		xmloperlist.add(xml);
	}

	private void push(String oper,LinkedHashMap<String,String> row,LinkedHashMap<String,String> rowold) throws Exception
	{
		if (destinationsync == null) return;

		XML xml = new XML();
		XML xmlop = xml.add(oper);

		ArrayList<String> destinationheader = destinationsync.getHeader();
		Set<String> keyset = new HashSet<String>(destinationheader);
		keyset.addAll(fields.getNames());
		String ignorestr = destinationsync.getXML().getAttribute("ignore_fields");
		if (ignorestr != null) keyset.removeAll(Arrays.asList(ignorestr.split("\\s*,\\s*")));

		for(String key:keyset)
		{
			String sourcevalue = row.get(key);

			String newvalue = sourcevalue;
			if (newvalue == null) newvalue = "";

			XML xmlrow;
			if (newvalue.indexOf("\n") == -1)
				xmlrow  = xmlop.add(key,newvalue);
			else
			{
				// This option is deprecated, use on_multiple on fields instead
				String ondupstr = sourcesync.getXML().getAttribute("on_duplicates");

				String[] duplist = null;
				String dupfields = sourcesync.getXML().getAttribute("on_duplicates_fields");
				if (dupfields != null) duplist = dupfields.split("\\s*,\\s*");

				if (duplist != null && Misc.indexOf(duplist,key) == -1);
				else if (ondupstr == null || ondupstr.equals("merge"));
				else if (ondupstr.equals("error"))
				{
					Misc.log("ERROR: [" + getDisplayKey(fields.getKeys(),row) + "] Rejecting record with a duplicated key on field " + key);
					return;
				}
				else if (ondupstr.equals("ignore"))
					return;
				else
					throw new AdapterException(sourcesync.getXML(),"Invalid on_duplicates attribute");
				xmlrow  = xmlop.addCDATA(key,newvalue);
			}

			if (Misc.isLog(30)) Misc.log("Is info check " + sourcevalue + ":" + key + ":" + destinationheader);
			boolean isinfo = (sourcevalue == null || !destinationheader.contains(key));
			if (isinfo) xmlrow.setAttribute("type","info");

			for(Field field:fields.getFields())
			{
				String fieldname = field.getName();
				if (key.equals(fieldname) && (field.isValid(sourcesync) || field.isValid(destinationsync)))
				{
					if (Misc.isLog(30)) Misc.log("Matched field " + fieldname + " with key " + key);
					String type = field.getType();
					if (type != null)
					{
						if (type.equals("key") && rowold != null && "".equals(newvalue))
						{
							String oldvalue = rowold.get(key);
							if (oldvalue != null)
								xmlrow.setValue(oldvalue);
						}
						if (!isinfo || !type.equals("infoapi"))
							xmlrow.setAttribute("type",type);
					}
				}
			}

			if (rowold != null)
			{
				String oldvalue = rowold.get(key);
				if (oldvalue == null) oldvalue = "";
				boolean issame = ignorecasefields ? oldvalue.equalsIgnoreCase(newvalue) : oldvalue.equals(newvalue);
				if (!issame && (!isinfo || oldvalue.length() > 0))
				{
					if (oldvalue.indexOf("\n") == -1)
						xmlrow.add("oldvalue",oldvalue);
					else
						xmlrow.addCDATA("oldvalue",oldvalue);
				}
			}
		}

		String changes = null;
		for(XML entry:xml.getElements())
		{
			String tag = entry.getTagName();
			String value = entry.getValue();
			if (value == null) value = "";
			String type = entry.getAttribute("type");
			if ("key".equals(type))
				;
			else if ("info".equals(type))
				;
			else if ("update".equals(oper))
			{
				XML old = entry.getElement("oldvalue");
				if (old != null)
				{
					String oldvalue = old.getValue();
					boolean initial = "initial".equals(type);
					if (!initial || ("initial".equals(type) && oldvalue == null))
					{
						if (oldvalue == null) oldvalue = "";
						String text = tag + "[" + oldvalue + "->" + value + "]";
						changes = changes == null ? text : changes + ", " + text;
					}
				}
			}
			else
			{
				String text = tag + ":" + value;
				changes = changes == null ? text : changes + ", " + text;
			}
			
		}

		String prevkeys = getDisplayKey(fields.getKeys(),row);
		if (prevkeys == null)
		{
			Misc.log("ERROR: Discarting record with null keys: " + row);
			return;
		}

		if ("update".equals(oper) && changes == null) return;
		if (Misc.isLog(2)) Misc.log(oper + ": " + prevkeys + (changes == null ? "" : " " + changes));

		if ("update".equals(oper)) counter.update++;
		else if ("add".equals(oper)) counter.add++;
		else if ("remove".equals(oper)) counter.remove++;
		xmlop.setAttribute("position","" + (counter.add + counter.remove + counter.update));

		if (directmode)
		{
			if (!dbsyncplugin.preview_mode && destinationsync != null) update.oper(destinationsync.getXML(),xml);
			return;
		}

		xmloperlist.add(xml);

		if (xmloperlist.size() >= maxqueuelength)
			flush();
	}

	private void remove(LinkedHashMap<String,String> row) throws Exception
	{
		if (doremove == doTypes.ERROR) Misc.log("ERROR: [" + getDisplayKey(fields.getKeys(),row) + "] removing entry rejected: " + row);
		if (doremove != doTypes.TRUE) return;
		if (Misc.isLog(4)) Misc.log("quick_remove: " + row);
		push("remove",row,null);
	}

	private void add(LinkedHashMap<String,String> row) throws Exception
	{
		if (doadd == doTypes.ERROR) Misc.log("ERROR: [" + getDisplayKey(fields.getKeys(),row) + "] adding entry rejected: " + row);
		if (doadd != doTypes.TRUE) return;
		if (destinationsync == null) return;
		if (Misc.isLog(4)) Misc.log("quick_add: " + row);
		push("add",row,null);
	}

	private void update(LinkedHashMap<String,String> rowold,LinkedHashMap<String,String> rownew) throws Exception
	{
		if (doupdate == doTypes.ERROR) Misc.log("ERROR: [" + getDisplayKey(fields.getKeys(),rownew) + "] updating entry rejected: " + rownew);
		if (doupdate != doTypes.TRUE) return;
		if (Misc.isLog(4))
		{
			String delta = null;
			for(String key:rownew.keySet())
			{
				String newvalue = rownew.get(key);
				if (newvalue == null) newvalue = "";
				String oldvalue = rowold.get(key);
				if (oldvalue == null) oldvalue = "";
				if (!oldvalue.equals(newvalue))
				{
					if (delta != null) delta += ", ";
					else delta = "";
					delta += key + "[" + oldvalue + "->" + newvalue + "]";
				}
			}

			Misc.log("quick_update: " + getDisplayKey(fields.getKeys(),rownew) + " " + delta);
		}
		push("update",rownew,rowold);
	}

	private String getKey(LinkedHashMap<String,String> row)
	{
		if (row == null) return "";
		StringBuilder key = new StringBuilder();

		for(String keyfield:fields.getKeys())
		{
			/* Use exclamation mark since it is the lowest ASCII character */
			/* This code must match db.getOrderBy logic */
			String keyvalue = row.get(keyfield);
			if (keyvalue != null) key.append(keyvalue.replace(' ','!').replace('_','!'));
			key.append("!");
		}

		return key.toString();
	}

	private Sync getSync(XML xml,XML xmlextra,XML xmlsource) throws Exception
	{
		String type = xml.getAttribute("type");
		if (type == null) type = "db";

		if (xmlextra != null)
		{
			String destfilter = xmlextra.getValue("remotefilter",null);
			if (destfilter == null)
				xml.removeAttribute("filter");
			else
				xml.setAttribute("filter",destfilter);
		}

		if (type.equals("db"))
			return new SyncSql(xml);
		else if (type.equals("csv"))
			return new SyncCsv(xml);
		/* SOAP data source is now obsolete and is replaced by XML data source */
		else if (type.equals("soap"))
			return new SyncSoap(xml);
		else if (type.equals("xml"))
			return new SyncXML(xml,xmlsource);
		else if (type.equals("class"))
			return new SyncClass(xml);

		throw new AdapterException(xml,"Invalid sync type " + type);
	}

	private void compare() throws Exception
	{
		xmloperlist = new ArrayList<XML>();
		counter = new RateCounter();

		if (Misc.isLog(2))
		{
			String sourcename = sourcesync.getName();
			String keyinfo = " (" + Misc.implode(fields.getKeys()) + ")";
			if (destinationsync == null)
				Misc.log("Reading source " + sourcename + keyinfo + "...");
			else
			{
				String destinationname = destinationsync.getName();
				Misc.log("Comparing source " + sourcename + " with destination " + destinationname + keyinfo + "...");
			}
		}

		push("start");

		LinkedHashMap<String,String> row = fields.getNext(sourcesync);
		LinkedHashMap<String,String> rowdest = (destinationsync == null) ? null : fields.getNext(destinationsync);

		/* keycheck is obsolete and should no longer be used */
		String keycheck = sourcesync.getXML().getAttribute("keycheck");
		boolean ischeck = !(keycheck != null && keycheck.equals("false"));

		if (checkcolumn && row != null && rowdest != null && ischeck)
		{
			String error = null;
			Set<String> keylist = fields.getKeys();

			if (!row.keySet().containsAll(keylist))
				error = "Source table must contain all keys: " + Misc.implode(keylist) + ": " + Misc.implode(row);

			if (!rowdest.keySet().containsAll(keylist))
				error = "Destination table must contain all keys: " + Misc.implode(keylist) + ": " + Misc.implode(rowdest);;

			if (error != null)
			{
				if (Misc.isLog(5)) Misc.log("Keys: " + keylist);
				if (Misc.isLog(5)) Misc.log("Source columns: " + row);
				if (Misc.isLog(5)) Misc.log("Destination columns: " + rowdest);
				throw new AdapterException("Synchronization " + dbsyncname + " cannot be done. " + error);
			}
		}

		String destkey = getKey(rowdest);
		String sourcekey = getKey(row);

		while(row != null || rowdest != null)
		{
			if (javaadapter.isShuttingDown()) return;
			if (tobreak) break;
			counter.total++;

			if (Misc.isLog(11)) Misc.log("Key source: " + sourcekey + " dest: " + destkey);

			if (rowdest != null && (row == null || (ignorecasekeys ? db.collator.compareIgnoreCase(sourcekey,destkey) : db.collator.compare(sourcekey,destkey)) > 0))
			{
				remove(rowdest);

				rowdest = fields.getNext(destinationsync);
				destkey = getKey(rowdest);

				continue;
			}

			if (row != null && (rowdest == null || (ignorecasekeys ? db.collator.compareIgnoreCase(sourcekey,destkey) : db.collator.compare(sourcekey,destkey)) < 0))
			{
				add(row);

				row = fields.getNext(sourcesync);
				sourcekey = getKey(row);

				continue;
			}

			if (row != null && (ignorecasekeys ? db.collator.compareIgnoreCase(sourcekey,destkey) : db.collator.compare(sourcekey,destkey)) == 0)
			{
				for(String key:destinationsync.getHeader())
				{
					String value = row.get(key);
					if (value == null) continue;
					String destvalue = rowdest.get(key);
					if (destvalue == null) destvalue = "";
					if (!value.equals(destvalue))
					{
						update(rowdest,row);
						break;
					}
				}

				row = fields.getNext(sourcesync);
				sourcekey = getKey(row);

				rowdest = fields.getNext(destinationsync);
				destkey = getKey(rowdest);

				continue;
			}
		}

		push("end");
		flush();
	}

	private void exec(XML xml,String oper) throws Exception
	{
		XML[] execlist = xml.getElements(oper);
		for(XML element:execlist)
		{
			String command = element.getValue();

			String type = element.getAttribute("type");
			if (type != null && type.equals("db"))
			{
				db.execsql(element.getAttribute("instance"),command);
				continue;
			}
			if (type != null && type.equals("xml"))
			{
				XML[] funclist = element.getElements("function");
				for(XML funcel:funclist)
				{
					Subscriber sub = new Subscriber(funcel);
					sub.run(new XML());
				}
				continue;
			}

			String charset = element.getAttribute("charset");
			Process process = Misc.exec(command,charset);
			int exitval = process.waitFor();
			if (exitval != 0)
				throw new AdapterException(element,"Command cannot be executed properly, result code is " + exitval);
		}
	}

	public void run() throws Exception
	{
		run(null,null);
	}

	public void run(XML xmlfunction) throws Exception
	{
		run(xmlfunction,null);
	}

	private doTypes getOperationFlag(XML xml,String attr) throws Exception
	{
		String dostr = xml.getAttribute(attr);
		if (dostr == null) return null;

		if (dostr.equals("true"))
			return doTypes.TRUE;
		else if (dostr.equals("false"))
			return doTypes.FALSE;
		else if (dostr.equals("error"))
			return doTypes.ERROR;
		else
			throw new AdapterException(xml,"Invalid " + attr + " attribute");
	}

	private Boolean getBooleanFlag(XML xml,String attr) throws Exception
	{
		String dostr = xml.getAttribute(attr);
		if (dostr == null) return null;

		if (dostr.equals("true"))
			return new Boolean(true);
		else if (dostr.equals("false"))
			return new Boolean(false);
		else
			throw new AdapterException(xml,"Invalid " + attr + " attribute");
	}
	private void setOperationFlags(XML xml) throws Exception
	{
		doTypes doresult = getOperationFlag(xml,"do_add");
		if (doresult != null) doadd = doresult;
		doresult = getOperationFlag(xml,"do_remove");
		if (doresult != null) doremove = doresult;
		doresult = getOperationFlag(xml,"do_update");
		if (doresult != null) doupdate = doresult;

		String casestr = xml.getAttribute("ignore_case");
		if (casestr != null)
		{
			if (casestr.equals("true"))
			{
				ignorecasekeys = true;
				ignorecasefields = true;
			}
			else if (casestr.equals("false"))
			{
				ignorecasekeys = false;
				ignorecasefields = false;
			}
			else if (casestr.equals("keys_only"))
			{
				ignorecasekeys = true;
				ignorecasefields = false;
			}
			else if (casestr.equals("non_keys_only"))
			{
				ignorecasekeys = false;
				ignorecasefields = true;
			}
			else
				throw new AdapterException(xml,"Invalid ignore_case attribute");
		}
	}

	private XML[] getFilenamePatterns(XML[] syncs) throws Exception
	{
		ArrayList<XML> results = new ArrayList<XML>();
		for(XML sync:syncs)
		{
			String filename = sync.getAttribute("filename");
			if (filename == null)
				results.add(sync);
			else
			{
				String fileescape = filename.replaceAll("\\.","\\.").replaceAll("\\*","\\*");
				Matcher matcherglob = Misc.substitutepattern.matcher(fileescape);
				String fileglob = matcherglob.replaceAll("*");
				if (Misc.isLog(10)) Misc.log("File glob: " + fileglob);

				fileescape = filename.replaceAll("[\\\\/]","[\\\\\\\\/]").replaceAll("\\.","\\.").replaceAll("\\*","\\.\\*");
				Matcher matchervar = Misc.substitutepattern.matcher(fileescape);
				String fileextract = matchervar.replaceAll("(.*)");
				if (Misc.isLog(10)) Misc.log("File extract: " + fileextract);
				Pattern patternextract = Pattern.compile(fileextract);

				Paths paths = new Paths(".",fileglob);
				String[] files = paths.getRelativePaths();
				if (files.length == 0) results.add(sync);

				for(String file:files)
				{
					XML newsync = sync.copy();

					if (Misc.isLog(10)) Misc.log("Filename: " + file);
					newsync.setAttribute("filename",file);

					Matcher matcherextract = patternextract.matcher(file);
					matchervar.reset();
					matchervar.find();
					int y = 0;
					while(matcherextract.find())
					{
						int count = matcherextract.groupCount();
						for(int x = 0;x < count;x++)
						{
							y++;
							if (y > matchervar.groupCount())
							{
								matchervar.find();
								y = 1;
							}
							if (Misc.isLog(10)) Misc.log("Variable from file name: " + matchervar.group(y) + "=" + matcherextract.group(x + 1));
							XML varxml = newsync.add("variable");
							varxml.setAttribute("name",matchervar.group(y));
							varxml.setAttribute("value",matcherextract.group(x + 1));
						}
					}

					results.add(newsync);
				}
			}
		}

		return results.toArray(syncs);
	}

	public void run(XML xmlfunction,XML xmlsource) throws Exception
	{
		XML xmlcfg = javaadapter.getConfiguration();

		startdate = new Date();

		XML[] elements = xmlcfg.getElements("dbsync");
		for(int i = 0;i < elements.length;i++)
		{
			if (javaadapter.isShuttingDown()) return;

			xmlsync = elements[i];
			if (!Misc.isFilterPass(xmlfunction,xmlsync)) continue;

			XML[] publishers = xmlsync.getElements("publisher");
			directmode = (publishers.length == 0);

			exec(xmlsync,"preexec");

			dbsyncname = xmlsync.getAttribute("name");
			Misc.log(1,"Syncing " + dbsyncname + "...");

			rootname = xmlsync.getAttribute("root");
			if (rootname == null) rootname = "ISMDatabaseUpdate";

			XML jms = xmlcfg.getElement("jms");
			maxqueuelength = jms == null ? 1 : DEFAULTMAXQUEUELENGTH; // Only JMS requires buffering
			String maxqueue = xmlsync.getAttribute("maxqueuelength");
			if (maxqueue != null) maxqueuelength = new Integer(maxqueue);

			/* checkcolumns is obsolete and should no longer be used */
			checkcolumn = true;
			String checkstr = xmlsync.getAttribute("checkcolumns");
			if (checkstr != null && checkstr.equals("false"))
				checkcolumn = false;

			String keyfield = xmlsync.getAttribute("keyfield");
			if (keyfield == null) keyfield = xmlsync.getAttribute("keyfields");
			String displaykeyfield = xmlsync.getAttribute("display_keyfield");
			displayfields = displaykeyfield == null ? null : new LinkedHashSet<String>(Arrays.asList(displaykeyfield.split("\\s*,\\s*")));

			XML[] sources = getFilenamePatterns(xmlsync.getElements("source"));
			XML[] destinations = xmlsync.getElements("destination");

			for(XML source:sources)
			{
				if (source == null) continue;

				String keyfield_source = source.getAttribute("keyfield");
				if (keyfield_source == null) keyfield_source = source.getAttribute("keyfields");
				if (keyfield_source != null) keyfield = keyfield_source;
				if (keyfield == null) throw new AdapterException(xmlsync,"keyfield is mandatory");

				int k = 0;
				for(;k < destinations.length;k++)
				{
					if (destinations[k] == null) continue;

					sourcesync = new Sync(source); // Set to a dummy sync since SortTable may call getName before sourcesync is initialised
					destinationsync = new Sync(destinations[k]);

					fields = new Fields(keyfield.split("\\s*,\\s*"));
					XML[] varsxml = source.getElements("variable");
					for(XML var:varsxml) fields.addDefaultVar(var);
					XML[] fieldsxml = xmlsync.getElements("field");
					for(XML field:fieldsxml) fields.add(field,Scope.SCOPE_GLOBAL);
					fieldsxml = source.getElements("field");
					for(XML field:fieldsxml) fields.add(field,Scope.SCOPE_SOURCE);
					fieldsxml = destinations[k].getElements("field");
					for(XML field:fieldsxml) fields.add(field,Scope.SCOPE_DESTINATION);

					doadd = doupdate = doremove = doTypes.TRUE;
					ignorecasekeys = ignorecasefields = false;
					setOperationFlags(xmlsync);
					setOperationFlags(source);
					setOperationFlags(destinations[k]);

					try
					{
						sourcesync = getSync(source,destinations[k],xmlsource);
					}
					catch(FileNotFoundException ex)
					{
						String onnotfound = source.getAttribute("on_file_not_found");
						if (onnotfound == null) onnotfound = source.getAttributeDeprecated("on_not_found");
						if (onnotfound == null || onnotfound.equals("exception"))
							Misc.rethrow(ex);
						else if (onnotfound.equals("ignore"))
							continue;
						else if (onnotfound.equals("warning"))
						{
							Misc.log("WARNING: Ignoring sync operation since file not found: " + ex.getMessage());
							continue;
						}
						else if (onnotfound.equals("error"))
						{
							Misc.log("ERROR: Ignoring sync operation since file not found: " + ex.getMessage());
							continue;
						}
						else
							throw new AdapterException(source,"Invalid on_file_not_found attribute");
					}
					destinationsync = getSync(destinations[k],source,xmlsource);

					compare();

					sourcesync = destinationsync = null;
					for(XML var:varsxml) fields.removeDefaultVar(var);
				}

				if (k == 0)
				{
					sourcesync = new Sync(source);

					fields = new Fields(keyfield.split("\\s*,\\s*"));
					XML[] varsxml = source.getElements("variable");
					for(XML var:varsxml) fields.addDefaultVar(var);
					XML[] fieldsxml = xmlsync.getElements("field");
					for(XML field:fieldsxml) fields.add(field,Scope.SCOPE_GLOBAL);
					fieldsxml = source.getElements("field");
					for(XML field:fieldsxml) fields.add(field,Scope.SCOPE_SOURCE);

					sourcesync = getSync(source,null,xmlsource);
					destinationsync = null;
					try
					{
						compare();
					}
					catch(java.net.SocketTimeoutException ex)
					{
						// Don't stop processing if a timeout occurs
						Misc.log(ex);
					}

					sourcesync = null;
					for(XML var:varsxml) fields.removeDefaultVar(var);
				}
			}

			exec(xmlsync,"postexec");

			lastdate = startdate;

			Misc.log(1,"Syncing " + dbsyncname + " done");
		}
	}

	public void close()
	{
		db.close();
	}
}
